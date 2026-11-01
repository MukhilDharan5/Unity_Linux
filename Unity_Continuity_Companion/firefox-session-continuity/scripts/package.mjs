import {readdir,readFile,mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {deflateRawSync} from 'node:zlib';
import {createHash} from 'node:crypto';
const table=Array.from({length:256},(_,n)=>{for(let i=0;i<8;i++)n=n&1?0xedb88320^(n>>>1):n>>>1;return n>>>0;});
function crc32(buffer){let crc=0xffffffff;for(const byte of buffer)crc=table[(crc^byte)&255]^(crc>>>8);return (crc^0xffffffff)>>>0;}
async function files(root,prefix=''){const out=[];for(const e of (await readdir(path.join(root,prefix),{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))){const relative=path.posix.join(prefix,e.name);if(e.isDirectory())out.push(...await files(root,relative));else if(e.isFile())out.push({name:relative,data:await readFile(path.join(root,relative))});}return out;}
async function zip(entries,destination){const data=[],central=[];let offset=0;for(const entry of entries){const name=Buffer.from(entry.name),compressed=deflateRawSync(entry.data,{level:9}),crc=crc32(entry.data),local=Buffer.alloc(30),index=Buffer.alloc(46);local.writeUInt32LE(0x04034b50);local.writeUInt16LE(20,4);local.writeUInt16LE(0x800,6);local.writeUInt16LE(8,8);local.writeUInt16LE(0x21,12);local.writeUInt32LE(crc,14);local.writeUInt32LE(compressed.length,18);local.writeUInt32LE(entry.data.length,22);local.writeUInt16LE(name.length,26);index.writeUInt32LE(0x02014b50);index.writeUInt16LE(20,4);index.writeUInt16LE(20,6);index.writeUInt16LE(0x800,8);index.writeUInt16LE(8,10);index.writeUInt16LE(0x21,14);index.writeUInt32LE(crc,16);index.writeUInt32LE(compressed.length,20);index.writeUInt32LE(entry.data.length,24);index.writeUInt16LE(name.length,28);index.writeUInt32LE(offset,42);data.push(local,name,compressed);central.push(index,name);offset+=local.length+name.length+compressed.length;}
 const directory=Buffer.concat(central),end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(entries.length,8);end.writeUInt16LE(entries.length,10);end.writeUInt32LE(directory.length,12);end.writeUInt32LE(offset,16);const output=Buffer.concat([...data,directory,end]);await writeFile(destination,output);return createHash('sha256').update(output).digest('hex');}
await mkdir('artifacts',{recursive:true});
const version=JSON.parse(await readFile('extension/manifest.json','utf8')).version;
const xpi=`artifacts/session-continuity-${version}-unsigned.xpi`;
const hash=await zip(await files('extension'),xpi);
const source=[];
for(const root of ['extension','tests','scripts','docs']){try{source.push(...(await files(root)).map(f=>({...f,name:`${root}/${f.name}`})));}catch(error){if(error.code!=='ENOENT')throw error;}}
for(const name of ['package.json','pnpm-lock.yaml','README.md','.gitignore']){try{source.push({name,data:await readFile(name)});}catch(error){if(error.code!=='ENOENT')throw error;}}
const sourceName=`artifacts/session-continuity-${version}-source.zip`,sourceHash=await zip(source,sourceName);
await writeFile('artifacts/SHA256SUMS.txt',`${hash}  ${path.basename(xpi)}\n${sourceHash}  ${path.basename(sourceName)}\n`);
console.log(`${xpi} (${hash})\n${sourceName} (${sourceHash})`);
