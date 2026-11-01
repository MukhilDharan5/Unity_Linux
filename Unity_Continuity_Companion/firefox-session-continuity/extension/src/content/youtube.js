export function youtubeVideoId(raw) {
  try {const u=new URL(raw);if(!['www.youtube.com','youtube.com','m.youtube.com'].includes(u.hostname))return null;
    const id=u.pathname==='/watch'?u.searchParams.get('v'):u.pathname.match(/^\/shorts\/([\w-]{11})(?:\/|$)/)?.[1];
    return /^[\w-]{11}$/.test(id||'')?id:null;
  } catch {return null;}
}
export function isYouTube(raw) {try{return /(^|\.)youtube\.com$/.test(new URL(raw).hostname);}catch{return false;}}
export function youtubeIdentity(media,raw) {
  const id=youtubeVideoId(raw);if(!id||media.localName!=='video')return null;
  const doc=media.ownerDocument;const player=media.closest('#movie_player,.html5-video-player');
  if(!player||player.matches('.ad-showing,.ad-interrupting'))return null;
  for(const overlay of player.querySelectorAll('.ytp-ad-player-overlay'))if(!overlay.hidden&&overlay.getClientRects().length&&doc.defaultView.getComputedStyle(overlay).visibility!=='hidden')return null;
  const watch=media.closest('ytd-watch-flexy[video-id]');
  const shorts=media.closest('ytd-reel-video-renderer[is-active][video-id]');
  // Require a second, DOM-owned content ID; URL alone is insufficient during SPA/ad transitions.
  const declared=watch?.getAttribute('video-id')||shorts?.getAttribute('video-id');
  if(declared!==id)return null;
  if(!watch&&!shorts)return null;
  if(doc.querySelector('.ytp-live-badge:not([disabled])')&&media.duration===Infinity)return null;
  return `youtube:${id}`;
}
