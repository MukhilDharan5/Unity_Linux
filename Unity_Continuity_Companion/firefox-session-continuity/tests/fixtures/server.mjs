import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const dir=new URL('./',import.meta.url);
function wav(){const samples=8000*900,b=Buffer.alloc(44+samples,128);b.write('RIFF',0);b.writeUInt32LE(36+samples,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(8000,24);b.writeUInt32LE(8000,28);b.writeUInt16LE(1,32);b.writeUInt16LE(8,34);b.write('data',36);b.writeUInt32LE(samples,40);return b;}
export async function createFixtureServer(port=0){
 const html=await readFile(new URL('index.html',dir)),audio=wav();
 const server=http.createServer(async(req,res)=>{
  const pathname=new URL(req.url,'http://localhost').pathname;
  let body=html,type='text/html; charset=utf-8';
  if(pathname==='/tone.wav'){body=audio;type='audio/wav';}
  if(pathname==='/sample.webm'){try{body=await readFile(new URL('sample.webm',dir));type='video/webm';}catch{res.writeHead(404).end();return;}}
  if(pathname==='/redirect'){res.writeHead(302,{Location:'/fixture?route=redirected'}).end();return;}
  const range=req.headers.range?.match(/^bytes=(\d+)-(\d*)$/);
  if(range){const start=Number(range[1]),end=range[2]?Math.min(Number(range[2]),body.length-1):body.length-1;res.writeHead(206,{'Content-Type':type,'Content-Range':`bytes ${start}-${end}/${body.length}`,'Content-Length':end-start+1,'Accept-Ranges':'bytes'});res.end(body.subarray(start,end+1));}
  else {res.writeHead(200,{'Content-Type':type,'Content-Length':body.length,'Accept-Ranges':'bytes','Cache-Control':'no-store'});res.end(body);}
 });
 await new Promise(resolve=>server.listen(port,'127.0.0.1',resolve));return {server,url:`http://127.0.0.1:${server.address().port}`};
}
if(process.argv[1]===fileURLToPath(import.meta.url)){const fixture=await createFixtureServer(8765);console.log(`Fixtures: ${fixture.url}/fixture?route=a#reading`);}
