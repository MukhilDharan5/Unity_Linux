import { build } from 'esbuild';
await build({entryPoints:['extension/src/content/main.js'], outfile:'extension/content.bundle.js', bundle:true, format:'iife', target:'firefox153', legalComments:'none', charset:'utf8'});
console.log('Built Firefox classic content script from ES modules.');
