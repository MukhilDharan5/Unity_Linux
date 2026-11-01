#!/usr/bin/env python3
"""Original gallery downloads and clipboard-only files over real TLS/D-Bus."""
import base64
import hashlib
import json
import os
from pathlib import Path
import unittest
from urllib.parse import urlsplit, unquote
import integration as h

class GalleryIntegration(h.Integration):
    @classmethod
    def start_daemon(cls):
        helpers = cls.dir / 'helpers'; helpers.mkdir(exist_ok=True)
        helper = helpers / 'wl-copy'
        helper.write_text('''#!/usr/bin/python3
import os,sys,time,uuid
from pathlib import Path
root=Path(os.environ['UNITY_TEST_GALLERY'])
if (root/'reject').exists():sys.exit(1)
payload=sys.stdin.read();token=uuid.uuid4().hex
(root/'clipboard').write_text(payload);(root/'owner').write_text(token)
if '--foreground' in sys.argv:
 while (root/'owner').read_text()==token:time.sleep(.02)
'''); helper.chmod(0o755)
        previous = dict(os.environ)
        try:
            os.environ.update(PATH=str(helpers)+':'+os.environ['PATH'], UNITY_TEST_GALLERY=str(cls.dir))
            h.Integration.start_daemon.__func__(cls)
        finally:
            os.environ.clear(); os.environ.update(previous)

    def test_end_to_end(self):
        info=json.loads(self.cli('discover').stdout)
        peer=h.Peer(self.port,info['fingerprint']);self.cli('pair',h.DEVICE)
        secret=peer.read()['secret'];peer.close();peer=h.Peer(self.port,info['fingerprint'],secret)
        self.addCleanup(peer.close)
        client=self.call(self.bus,h.MANAGER,'org.unityconnect.Manager1','RequestAccess','(sas)',('Gallery test',['read','control']))
        self.cli('authorize',client);path='/org/unityconnect/devices/d_'+h.DEVICE
        def state(op):return json.loads(self.call(self.bus,op,'org.unityconnect.Operation1','GetState'))
        def request(action):
            op=self.call(self.bus,path,'org.unityconnect.Device1','Execute','(sss)',('photos',action,json.dumps({'id':'ab'*16})))
            while True:
                command=peer.read()
                if command['type']=='ping':peer.send({'type':'pong'})
                elif command['type']=='command':break
            self.assertEqual(command['action'],'get');self.assertEqual(command['id'],command['data']['transfer_id'])
            return op,command
        def transfer(id,name,data):
            peer.event('files',{'phase':'begin','id':id,'name':name,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()})
            peer.event('files',{'phase':'chunk','id':id,'data':base64.b64encode(data).decode()})
            peer.event('files',{'phase':'end','id':id})
        def finish(command,transfer_id=None):
            peer.send({'type':'result','id':command['id'],'ok':True,'error':'','data':{'state':'transferred','transfer_id':transfer_id or command['id']}})
        original=b'video original bytes\x00\xff'
        op,command=request('get');transfer(command['id'],'kept.mp4',original);finish(command)
        h.wait_for(lambda:state(op)['status']=='completed')
        kept=self.state/'received'/'kept.mp4';self.assertEqual(kept.read_bytes(),original)
        self.assertEqual(state(op)['result']['received_path'],str(kept))
        notices=len(self.notices.items)
        op,command=request('copy');transfer(command['id'],'தமிழ் # video.mp4',original);finish(command)
        h.wait_for(lambda:state(op)['status']=='completed')
        first=Path(unquote(urlsplit((self.dir/'clipboard').read_text().strip()).path))
        self.assertEqual(first.read_bytes(),original);self.assertIn('clipboard-files',str(first))
        self.assertEqual(first.name,'தமிழ் # video.mp4')
        self.assertEqual(len(self.notices.items),notices,'A clipboard download must not create a permanent received-file alert')
        # Repeated pastes retain the same original; replacing ownership removes it.
        self.assertEqual(first.read_bytes(),original);self.assertTrue(kept.exists())
        op,command=request('copy');transfer(command['id'],'next.jpg',b'next original');finish(command)
        h.wait_for(lambda:state(op)['status']=='completed')
        second=Path(unquote(urlsplit((self.dir/'clipboard').read_text().strip()).path))
        h.wait_for(lambda:not first.exists());self.assertEqual(second.read_bytes(),b'next original')
        (self.dir/'owner').write_text('another application copied text')
        h.wait_for(lambda:not second.exists());self.assertEqual(kept.read_bytes(),original)
        # Failed ownership and mismatched operation IDs leave no temporary copies.
        (self.dir/'reject').touch()
        op,command=request('copy');transfer(command['id'],'failed.mp4',original);finish(command)
        h.wait_for(lambda:state(op)['status']=='failed')
        h.wait_for(lambda:not list((self.state/'clipboard-files').iterdir()))
        (self.dir/'reject').unlink()
        op,command=request('copy');transfer(command['id'],'mismatch.mp4',original);finish(command,'ef'*16)
        h.wait_for(lambda:state(op)['status']=='failed')
        self.assertFalse(list((self.state/'clipboard-files').iterdir()))
        self.assertEqual(kept.read_bytes(),original)
        # A successful-looking result without the matching verified file cannot be dragged.
        op,command=request('get');finish(command)
        h.wait_for(lambda:state(op)['status']=='failed')
        self.assertNotIn('received_path',state(op).get('result') or {})
        self.cli('feature',h.DEVICE,'photos','--disable')
        from gi.repository import GLib
        with self.assertRaises(GLib.Error):request('copy')

if __name__=='__main__':unittest.main(verbosity=2)
