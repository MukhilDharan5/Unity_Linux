//! Password-first PAM conversation; only explicit phone approval reaches the relay.
use anyhow::{ensure, Result};
use std::{
    ffi::{c_char, c_int, c_void, CStr},
    io::Write,
    os::fd::AsRawFd,
    time::{Duration, Instant},
};
use unity_auth::{Challenge, Enrollment};
const PAM_SUCCESS: c_int = 0;
const PAM_IGNORE: c_int = 25;
const PAM_AUTHTOK: c_int = 6;
const PAM_SILENT: c_int = 0x8000;
#[link(name = "pam")]
extern "C" {
    fn pam_get_user(pamh: *mut c_void, user: *mut *const c_char, prompt: *const c_char) -> c_int;
    fn pam_get_item(pamh: *const c_void, item: c_int, value: *mut *const c_void) -> c_int;
    fn pam_set_item(pamh: *mut c_void, item: c_int, value: *const c_void) -> c_int;
    fn pam_get_authtok(
        pamh: *mut c_void,
        item: c_int,
        token: *mut *const c_char,
        prompt: *const c_char,
    ) -> c_int;
    fn pam_prompt(
        pamh: *mut c_void,
        style: c_int,
        response: *mut *mut c_char,
        format: *const c_char,
        ...
    ) -> c_int;
}
/// libpam owns the secret buffer. Never copy, log, validate or relay its contents.
/// A nonempty token is left for the unchanged password stack to verify.
unsafe fn choose_phone(pamh: *mut c_void, flags: c_int, service: &str) -> Result<bool> {
    ensure!(
        flags & PAM_SILENT == 0,
        "Noninteractive authentication uses the normal stack"
    );
    if matches!(service, "unity-pam-test" | "gdm-fingerprint") {
        return Ok(true);
    }
    let mut existing = std::ptr::null();
    ensure!(
        pam_get_item(pamh, PAM_AUTHTOK, &mut existing) == PAM_SUCCESS,
        "PAM token unavailable"
    );
    if !existing.is_null() {
        return Ok(false);
    }
    ensure!(pam_prompt(pamh, 4, std::ptr::null_mut(), c"Enter your laptop password, or leave it blank and press Enter to use phone fingerprint.".as_ptr()) == PAM_SUCCESS, "Authentication choice conversation unavailable");
    let mut token = std::ptr::null();
    ensure!(
        pam_get_authtok(pamh, PAM_AUTHTOK, &mut token, c"Password: ".as_ptr()) == PAM_SUCCESS
            && !token.is_null(),
        "Password conversation unavailable"
    );
    if *token != 0 {
        return Ok(false);
    }
    // An empty response opts into phone approval, never a blank password. Clearing
    // it also lets the normal stack prompt afresh if phone approval does not succeed.
    ensure!(
        pam_set_item(pamh, PAM_AUTHTOK, std::ptr::null()) == PAM_SUCCESS,
        "Could not clear empty PAM token"
    );
    Ok(true)
}
unsafe fn item(pamh: *mut c_void, kind: c_int) -> Result<String> {
    let mut value = std::ptr::null();
    ensure!(
        pam_get_item(pamh, kind, &mut value) == 0,
        "PAM context unavailable"
    );
    if value.is_null() {
        return Ok(String::new());
    }
    let bytes = CStr::from_ptr(value.cast()).to_bytes();
    ensure!(bytes.len() <= 128, "PAM context too long");
    Ok(std::str::from_utf8(bytes)?.into())
}
unsafe fn authenticate(pamh: *mut c_void, flags: c_int, argc: c_int) -> Result<()> {
    ensure!(
        !pamh.is_null() && argc == 0 && libc::geteuid() == 0,
        "Root PAM context required"
    );
    let mut user = std::ptr::null();
    ensure!(
        pam_get_user(pamh, &mut user, std::ptr::null()) == 0 && !user.is_null(),
        "Account unavailable"
    );
    let username = CStr::from_ptr(user).to_str()?;
    let uid = unity_auth::account(username)?;
    let e = Enrollment::load(uid)?;
    ensure!(e.username == username, "Account enrollment mismatch");
    let service = item(pamh, 1)?;
    let tty = item(pamh, 3)?;
    let host = item(pamh, 4)?;
    let remote_user = item(pamh, 8)?;
    ensure!(
        unity_auth::local_pam_host(&host),
        "Remote PAM authentication is not enrolled"
    );
    ensure!(e.services.contains(&service), "PAM service is not enrolled");
    ensure!(
        choose_phone(pamh, flags, &service)?,
        "Use the normal password stack"
    );
    let challenge = Challenge::new(&e, service, tty, remote_user)?;
    let path = unity_auth::socket_path(uid);
    unity_auth::regular_socket(std::path::Path::new(&path), uid)?;
    let mut stream = unity_auth::connect_broker(&path)?;
    if flags & PAM_SILENT == 0 {
        pam_prompt(pamh,4,std::ptr::null_mut(),c"Phone fingerprint selected. Approve on your phone, or choose Use laptop password there to return to password entry.".as_ptr());
    }
    let mut cred: libc::ucred = std::mem::zeroed();
    let mut len = std::mem::size_of_val(&cred) as libc::socklen_t;
    ensure!(
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut cred as *mut libc::ucred).cast(),
            &mut len
        ) == 0
            && cred.uid == uid,
        "Unexpected authentication broker owner"
    );
    stream.set_write_timeout(Some(Duration::from_secs(2)))?;
    stream.set_read_timeout(Some(Duration::from_secs(e.timeout_seconds)))?;
    let bytes = serde_json::to_vec(&challenge)?;
    let started = Instant::now();
    stream.write_all(&(bytes.len() as u32).to_be_bytes())?;
    stream.write_all(&bytes)?;
    let response: serde_json::Value = serde_json::from_slice(&unity_auth::read_frame_deadline(
        &mut stream,
        started + Duration::from_secs(e.timeout_seconds),
    )?)?;
    ensure!(
        started.elapsed() < Duration::from_secs(e.timeout_seconds),
        "Authentication timed out"
    );
    challenge.verify(
        &e.public_key,
        response["signature"].as_str().unwrap_or(""),
        unity_auth::now()?,
    )
}
/// # Safety
/// Called only by libpam with its opaque handle and module argument array.
#[no_mangle]
pub unsafe extern "C" fn pam_sm_authenticate(
    pamh: *mut c_void,
    flags: c_int,
    argc: c_int,
    _argv: *const *const c_char,
) -> c_int {
    match std::panic::catch_unwind(|| authenticate(pamh, flags, argc)) {
        Ok(Ok(())) => PAM_SUCCESS,
        _ => PAM_IGNORE,
    }
}
/// # Safety
/// Called by libpam. The module never supplies account credentials.
#[no_mangle]
pub unsafe extern "C" fn pam_sm_setcred(
    _pamh: *mut c_void,
    _flags: c_int,
    _argc: c_int,
    _argv: *const *const c_char,
) -> c_int {
    PAM_IGNORE
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn null_or_unprivileged_context_never_authenticates() {
        unsafe {
            assert_eq!(
                pam_sm_authenticate(std::ptr::null_mut(), 0, 0, std::ptr::null()),
                PAM_IGNORE
            );
        }
    }
}
