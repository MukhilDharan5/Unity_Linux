import net from 'node:net';
import {spawn} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
export const wait=ms=>new Promise(r=>setTimeout(r,ms));
export async function until(fn,ms=15000){const end=Date.now()+ms;let value;while(Date.now()<end){value=await fn();if(value)return value;await wait(100);}throw new Error('Condition timed out');}
export class Marionette {
 constructor(socket){this.socket=socket;this.buffer=Buffer.alloc(0);this.pending=new Map();this.id=0;socket.on('data',chunk=>{this.buffer=Buffer.concat([this.buffer,chunk]);this.drain();});socket.on('error',error=>{for(const p of this.pending.values())p.reject(error);});socket.on('close',()=>{for(const p of this.pending.values())p.reject(new Error('Firefox connection closed'));this.pending.clear();});}
 drain(){while(true){const separator=this.buffer.indexOf(':');if(separator<0)return;const length=Number(this.buffer.subarray(0,separator).toString());if(this.buffer.length<separator+1+length)return;const raw=this.buffer.subarray(separator+1,separator+1+length).toString();this.buffer=this.buffer.subarray(separator+1+length);const message=JSON.parse(raw);if(!Array.isArray(message)){this.hello=message;continue;}const p=this.pending.get(message[1]);if(!p)continue;this.pending.delete(message[1]);clearTimeout(p.timer);if(message[2])p.reject(new Error(JSON.stringify(message[2])));else p.resolve(message[3]);}}
 call(command,parameters={}){const id=++this.id;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error(`Marionette timeout: ${command}`));},35000);this.pending.set(id,{resolve,reject,timer});const json=JSON.stringify([0,id,command,parameters]);this.socket.write(`${Buffer.byteLength(json)}:${json}`);});}
 async script(script,args=[]){const r=await this.call('WebDriver:ExecuteScript',{script,args,newSandbox:false,sandbox:null,line:1,filename:'continuity-test'});return r?.value??r;}
 async asyncScript(script,args=[]){const r=await this.call('WebDriver:ExecuteAsyncScript',{script,args,newSandbox:false,sandbox:null,scriptTimeout:30000,line:1,filename:'continuity-test'});return r?.value??r;}
 async context(value){await this.call('Marionette:SetContext',{value});}
 async navigate(url){await this.call('WebDriver:Navigate',{url});}
}
export async function startFirefox({binary='C:\\Program Files\\Mozilla Firefox\\firefox.exe',profile,port=2828,prefs={}}){
 const root=path.resolve('.test-profiles');profile=path.resolve(profile);
 if(!profile.startsWith(root+path.sep))throw new Error('Tests require a dedicated profile under .test-profiles');
 await mkdir(profile,{recursive:true});
 const settings={'marionette.port':port,'browser.shell.checkDefaultBrowser':false,'browser.startup.homepage_override.mstone':'ignore','browser.startup.homepage':'about:blank','browser.startup.page':0,'browser.aboutwelcome.enabled':false,'datareporting.policy.dataSubmissionEnabled':false,'toolkit.telemetry.reportingpolicy.firstRun':false,...prefs};
 await writeFile(path.join(profile,'user.js'),Object.entries(settings).map(([k,v])=>`user_pref(${JSON.stringify(k)}, ${JSON.stringify(v)});`).join('\n'));
 const child=spawn(binary,['-no-remote','-headless','-profile',profile,'-marionette','-remote-allow-system-access'],{windowsHide:true,env:{...process.env,MOZ_REMOTE_ALLOW_SYSTEM_ACCESS:'1'},stdio:['ignore','pipe','pipe']});
 let logs='';child.stdout.on('data',x=>{logs+=x;});child.stderr.on('data',x=>{logs+=x;});
 const socket=await until(()=>new Promise(resolve=>{const s=net.connect({port,host:'127.0.0.1'});s.once('connect',()=>resolve(s));s.once('error',()=>{s.destroy();resolve(null);});}),20000).catch(e=>{child.kill();throw new Error(e.message+' '+logs.slice(-1500));});
 const client=new Marionette(socket);await until(()=>client.hello);const session=await client.call('WebDriver:NewSession',{capabilities:{alwaysMatch:{acceptInsecureCerts:false}}});
 return {client,child,session,profile,logs:()=>logs,async stop(){try{await client.call('Marionette:Quit',{flags:['eForceQuit']});}catch{}socket.destroy();if(child.exitCode===null){await Promise.race([new Promise(r=>child.once('exit',r)),wait(5000)]);if(child.exitCode===null)child.kill();}}};
}
