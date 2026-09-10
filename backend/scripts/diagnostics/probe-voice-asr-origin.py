"""Six fresh-session paced ASR origin checks; no mic or generated response."""
import asyncio,aiohttp,array,base64,hashlib,json,math,os,time
from pathlib import Path
ROOT=Path.cwd(); OUT=ROOT/'build/voice-asr-origin/paced-confirmation'; OUT.mkdir(parents=True,exist_ok=True)
RATE=24000
zero=bytes(RATE*2*2)
values=array.array('h',[0])*(RATE*5)
for i in range(48): values[RATE*2+i]=round(0.07*32767*math.sin(i*2.7)*math.exp(-i/7))
click=values.tobytes()
key=os.environ.get('OPENAI_API_KEY_USER')
if not key:
    raise SystemExit('Set OPENAI_API_KEY_USER in the environment before running this opt-in probe.')
rows=[]
def emit(row):
    print(json.dumps(row,ensure_ascii=False),flush=True)
    with (OUT/'events.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
async def run_case(client,case,pcm,near):
    async with client.ws_connect('wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1',headers={'Authorization':'Bearer '+key},timeout=aiohttp.ClientWSTimeout(ws_receive=15)) as ws:
        async def wait(kind,target=None):
            for _ in range(100):
                event=await ws.receive_json(); typ=event.get('type','')
                if typ=='error':raise RuntimeError('provider_error:'+event.get('error',{}).get('code','unknown'))
                if typ.startswith('response.'):raise RuntimeError('unexpected_response')
                if typ=='conversation.item.input_audio_transcription.failed':raise RuntimeError('transcription_failed')
                if typ==kind and (target is None or event.get('item_id')==target):return event
            raise RuntimeError('event_limit')
        await wait('session.created')
        await ws.send_json({'type':'session.update','session':{'type':'realtime','output_modalities':['text'],'instructions':'','tools':[], 'audio':{'input':{'format':{'type':'audio/pcm','rate':RATE},'turn_detection':None,'noise_reduction':{'type':'near_field'} if near else None,'transcription':{'model':'gpt-4o-mini-transcribe','language':'ko'}}}}})
        effective=(await wait('session.updated'))['session']; inp=effective['audio']['input']
        config={'model':effective.get('model'),'transcription':inp.get('transcription'),'noise_reduction':inp.get('noise_reduction'),'turn_detection':inp.get('turn_detection')}
        started=time.monotonic(); scheduled=started
        # Each chunk carries exactly 250ms PCM. Sleep through its duration before next chunk/commit.
        chunk_bytes=RATE*2//4
        for offset in range(0,len(pcm),chunk_bytes):
            chunk=pcm[offset:offset+chunk_bytes]
            await ws.send_json({'type':'input_audio_buffer.append','audio':base64.b64encode(chunk).decode()})
            scheduled+=len(chunk)/(RATE*2)
            await asyncio.sleep(max(0,scheduled-time.monotonic()))
        committed_at=time.monotonic(); await ws.send_json({'type':'input_audio_buffer.commit'})
        item=(await wait('input_audio_buffer.committed'))['item_id']
        final=await wait('conversation.item.input_audio_transcription.completed',item)
        samples=array.array('h');samples.frombytes(pcm)
        row={'case':case,'fresh_session':True,'effective':config,'sha256':hashlib.sha256(pcm).hexdigest(),'duration_ms':len(pcm)/(RATE*2)*1000,'chunk_duration_ms':250,'rms_normalized':math.sqrt(sum((v/32768)**2 for v in samples)/len(samples)),'upload_ms':round((committed_at-started)*1000,2),'commit_to_asr_ms':round((time.monotonic()-committed_at)*1000,2),'transcript':final.get('transcript'),'response_create_sent':False}
        rows.append(row);emit(row)
async def main():
    async with aiohttp.ClientSession() as client:
        for near in [True,False]:
            for name,pcm in [('silence_2s_repeat_1',zero),('silence_2s_repeat_2',zero),('click_5s',click)]:
                await run_case(client,('near_field_' if near else 'noise_null_')+name,pcm,near)
try:
    asyncio.run(asyncio.wait_for(main(),120))
except Exception as e:
    row={'event':'failure','type':type(e).__name__,'completed_cases':len(rows)}
    if isinstance(e,RuntimeError):row['safe_detail']=str(e)
    emit(row)
    raise SystemExit(1)
finally:
    (OUT/'results.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
