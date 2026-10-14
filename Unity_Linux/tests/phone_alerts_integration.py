#!/usr/bin/env python3
"""Real TLS events and native desktop push notifications on a private bus."""
import json
import time
import unittest
import integration as h
from fake_notifications import FakeNotifications

class PhoneAlerts(h.Integration):
    def test_end_to_end(self):
        info=json.loads(self.cli('discover').stdout)
        peer=h.Peer(self.port,info['fingerprint']); self.cli('pair',h.DEVICE)
        secret=peer.read()['secret']; peer.close()
        peer=h.Peer(self.port,info['fingerprint'],secret)
        def notice(count): return h.wait_for(lambda:self.notices.items[count-1] if len(self.notices.items)>=count else None)
        def event(id,text='Hello <b>தமிழ்</b> & welcome',**extra):
            data={'id':id,'app':'test.chat','app_name':'Chat','title':'Alex','text':text,'timestamp':int(time.time()*1000),'actions':[],'removed':False,'alert':True}
            data.update(extra);peer.event('notifications',data);return data
        # A reconnect/list snapshot populates app state without historical popups.
        quiet=event('old',alert=False,sms_id='41');time.sleep(.15);self.assertEqual(self.notices.items,[])
        legacy=dict(quiet,id='legacy-old',timestamp=1);legacy.pop('alert')
        peer.event('notifications',legacy);time.sleep(.1);self.assertEqual(self.notices.items,[])
        posted=event('new');first=notice(1)
        self.assertEqual(first['title'],'Chat · Alex')
        self.assertIn('&lt;b&gt;தமிழ்&lt;/b&gt; &amp;',first['body'])
        self.assertEqual(first['hints']['category'],'im.received')
        self.assertEqual(first['actions'],['default','Open in Unity','open','Open in Unity'])
        peer.event('notifications',posted);time.sleep(.15);self.assertEqual(len(self.notices.items),1)
        posted.update(text='Updated message',timestamp=posted['timestamp']+1);peer.event('notifications',posted)
        second=notice(2);self.assertEqual(second['replaces'],first['id']);self.assertTrue(second['hints']['suppress-sound'])
        peer.event('notifications',dict(posted,removed=True));h.wait_for(lambda:second['id'] in self.notices.closed)
        # Incoming SMS works with mirroring disabled and never alerts on reports/revisions.
        self.cli('feature',h.DEVICE,'notifications','--disable')
        event('blocked');peer.event('sms',{'revision':1})
        peer.event('sms',{'revision':2,'message_id':'ab'*16,'state':'delivered'})
        time.sleep(.15);self.assertEqual(len(self.notices.items),2)
        sms={'revision':3,'incoming':{'id':'41','address':'+15550123','body':'New SMS <test>','timestamp':int(time.time()*1000)}}
        peer.event('sms',sms);third=notice(3)
        self.assertEqual(third['title'],'SMS · +15550123');self.assertIn('&lt;test&gt;',third['body'])
        peer.event('sms',sms);time.sleep(.15);self.assertEqual(len(self.notices.items),3)
        # The same exact inbox row from the SMS-app mirror replaces, not duplicates.
        self.cli('feature',h.DEVICE,'notifications')
        mirror=event('sms-app',text='New SMS <test>',sms_id='41',app='test.sms',app_name='Messages')
        fourth=notice(4);self.assertEqual(fourth['replaces'],third['id'])
        peer.event('sms',sms);time.sleep(.15);self.assertEqual(len(self.notices.items),4)
        peer.event('notifications',dict(mirror,removed=True));h.wait_for(lambda:fourth['id'] in self.notices.closed)
        # Independent messages with equal text have distinct row IDs and both push.
        sms['incoming']['id']='42';peer.event('sms',sms);fifth=notice(5)
        sms['incoming']['id']='43';peer.event('sms',sms);sixth=notice(6)
        self.assertEqual(fifth['replaces'],0);self.assertEqual(sixth['replaces'],0)
        self.cli('feature',h.DEVICE,'sms','--disable')
        h.wait_for(lambda:all(n['id'] in self.notices.closed for n in (fifth,sixth)))
        sms['incoming']['id']='44';peer.event('sms',sms);time.sleep(.15);self.assertEqual(len(self.notices.items),6)
        self.cli('feature',h.DEVICE,'sms')
        self.notices.dismiss(fifth['id']);peer.event('sms',dict(sms,incoming=dict(sms['incoming'],id='42')))
        time.sleep(.15);self.assertEqual(len(self.notices.items),6)
        # Disconnect removes mirrored private popups, and reconnect snapshots stay quiet.
        event('disconnect');seventh=notice(7);peer.close()
        h.wait_for(lambda:seventh['id'] in self.notices.closed)
        peer=h.Peer(self.port,info['fingerprint'],secret)
        peer.event('notifications',dict(posted,alert=False,removed=False));time.sleep(.15);self.assertEqual(len(self.notices.items),7)
        # No desktop server must not break the phone transport; later new posts recover.
        self.notices.available=False;event('unavailable');h.wait_for(lambda:self.notices.unavailable_queries)
        peer.send({'type':'ping'});self.assertEqual(peer.read()['type'],'pong')
        self.notices.close();type(self).notices=FakeNotifications()
        event('restored');self.assertEqual(notice(1)['title'],'Chat · Alex')
        peer.close()

if __name__=='__main__':unittest.main(verbosity=2)
