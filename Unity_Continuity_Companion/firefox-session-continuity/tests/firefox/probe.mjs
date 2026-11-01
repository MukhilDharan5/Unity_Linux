import {startFirefox} from './marionette.mjs';
import path from 'node:path';
const run=await startFirefox({profile:path.resolve('.test-profiles/probe-'+Date.now()),port:2837});
try{
 console.log('SESSION',JSON.stringify(run.session));
 console.log('INSTALL',await run.client.call('Addon:Install',{path:path.resolve('extension'),temporary:true}));
 await run.client.context('chrome');
 const ids=await run.client.script('return Services.prefs.getStringPref("extensions.webextensions.uuids");');
 const uuid=JSON.parse(ids)['session-continuity@local.invalid'];console.log('UUID',uuid);
 await run.client.context('content');await run.client.navigate(`moz-extension://${uuid}/ui/options.html`);
 console.log('API',await run.client.script('return [typeof browser, document.title];'));
 console.log('STATUS',await run.client.asyncScript('const done=arguments[arguments.length-1];browser.runtime.sendMessage({type:"STATUS"}).then(done,e=>done({error:String(e)}));'));
}finally{await run.stop();}
