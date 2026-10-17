import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, Alert, BackHandler, SafeAreaView, StatusBar, Text, View} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {pick, types} from '@react-native-documents/picker';
import RNFS from 'react-native-fs';
import {WebView, WebViewMessageEvent} from 'react-native-webview';
import {initLlama, LlamaContext} from '@pocketpalai/llama.rn';

const MODEL_KEY = 'sandev.activeModelPath';
const STOP = ['</s>', '<|end|>', '<|eot_id|>', '<|end_of_text|>', '<|im_end|>', '<|EOT|>', '<|END_OF_TURN_TOKEN|>', '<|end_of_turn|>', '<|endoftext|>'];
const BRIDGE = `(function(){
  var seq=0,pending={};
  window.__sandevResolve=function(id,value,error){var p=pending[id];if(!p)return;delete pending[id];error?p.reject(new Error(String(value))):p.resolve(value);};
  window.__sandevSetModelLoaded=function(v){window.__sandevModelLoaded=!!v;};
  window.__sandevModelLoaded=false;
  function call(target,method,args){
    if(method==='isLoaded'||method==='isModelLoaded'||method==='isLoadedModel')return !!window.__sandevModelLoaded;
    if(/(pick|select|choose).*model|model.*(pick|select|choose)/i.test(method)){window.ReactNativeWebView.postMessage(JSON.stringify({target:target,method:String(method),args:args,requestId:0}));return;}
    var id=++seq;
    return new Promise(function(resolve,reject){pending[id]={resolve:resolve,reject:reject};window.ReactNativeWebView.postMessage(JSON.stringify({target:target,method:String(method),args:args,requestId:id}));});
  }
  function proxy(target){return new Proxy({}, {get:function(_,method){if(method==='then')return undefined;return function(){return call(target,String(method),Array.prototype.slice.call(arguments));};}});}
  window.Sandev=proxy('Sandev');window.Android=proxy('Android');
})(); true;`;

function parsePrompt(prompt: string): {role: 'system'|'user'|'assistant'; content: string}[] {
  const sys = '[[SANDEV_SYSTEM]]\n';
  if (!prompt.startsWith(sys)) return [{role: 'user', content: prompt}];
  const rest = prompt.slice(sys.length);
  const marker = /\n\[\[SANDEV_HISTORY_(USER|ASSISTANT)\]\]\n/g;
  const first = marker.exec(rest);
  const messages: {role: 'system'|'user'|'assistant'; content: string}[] = [
    {role: 'system', content: first ? rest.slice(0, first.index) : rest},
  ];
  if (!first) return messages;
  let current: RegExpExecArray | null = first;
  while (current) {
    const bodyStart = current.index + current[0].length;
    const next = marker.exec(rest);
    messages.push({
      role: current[1] === 'USER' ? 'user' : 'assistant',
      content: rest.slice(bodyStart, next ? next.index : rest.length).trim(),
    });
    current = next;
  }
  return messages;
}

export default function App(): React.JSX.Element {
  const web = useRef<WebView>(null);
  const ctx = useRef<LlamaContext | null>(null);
  const busy = useRef(false);
  const [status, setStatus] = useState('Sandev Chat');
  const [booting, setBooting] = useState(true);

  const inject = useCallback((js: string) => web.current?.injectJavaScript(js + ';true;'), []);
  const reply = useCallback((id: number, value: unknown, failed = false) => {
    if (!id) return;
    inject('window.__sandevResolve&&window.__sandevResolve(' + JSON.stringify(id) + ',' + JSON.stringify(String(value ?? '')) + ',' + JSON.stringify(failed) + ')');
  }, [inject]);

  const chooseModel = useCallback(async () => {
    try {
      const pickedFiles = await pick({type: [types.allFiles], mode: 'import'});
      const picked = pickedFiles[0];
      if (!picked) return;
      const uri = picked.uri;
      const path = decodeURIComponent(uri.replace(/^file:\/\//, ''));
      if (!path.toLowerCase().endsWith('.gguf')) {
        Alert.alert('Model tidak valid', 'Pilih file model berformat .gguf.');
        return;
      }
      setStatus('Memuat model…');
      await ctx.current?.release();
      ctx.current = null;
      // CPU is the safe fallback. Do not force GPU/NPU on unsupported devices.
      ctx.current = await initLlama({
        model: 'file://' + path,
        n_ctx: 2048,
        n_batch: 256,
        n_threads: 4,
        n_gpu_layers: 0,
        use_mlock: false,
      });
      await AsyncStorage.setItem(MODEL_KEY, path);
      setStatus('Model siap');
      inject('window.__sandevSetModelLoaded(true);if(window.onModelLoaded)window.onModelLoaded(' + JSON.stringify(picked.name || 'Model GGUF') + ')');
    } catch (e: any) {
      if (true) {
        const message = e?.message || 'Gagal memuat model.';
        setStatus('Model belum siap');
        inject('window.__sandevSetModelLoaded(false);if(window.onModelLoadError)window.onModelLoadError(' + JSON.stringify(message) + ')');
        Alert.alert('Gagal memuat model', message);
      }
    }
  }, [inject]);

  const onMessage = useCallback(async (event: WebViewMessageEvent) => {
    let data: any;
    try { data = JSON.parse(event.nativeEvent.data); } catch { return; }
    if (data.__sandevDiag === 'tap') {
      const target = [data.target, data.id ? '#' + data.id : '', data.label || ''].filter(Boolean).join(' ');
      setStatus('UI tap received: ' + target.slice(0, 100));
      return;
    }
    if (data.__sandevDiag === 'error' || data.__sandevDiag === 'rejection') {
      const detail = String(data.message || 'Unknown JavaScript error').slice(0, 220);
      setStatus('UI JS ' + data.__sandevDiag + ': ' + detail);
      console.warn('[SANDEV WebView]', data.__sandevDiag, detail, data.source || '', data.line || '');
      return;
    }
    const method = String(data.method || '');
    const id = Number(data.requestId || 0);
    const args = Array.isArray(data.args) ? data.args : [];
    if (/(pick|select|choose).*model|model.*(pick|select|choose)/i.test(method)) {
      await chooseModel();
      return;
    }
    if (method === 'generate' || method === 'generateText' || method === 'completion') {
      if (busy.current) { reply(id, 'Generasi sebelumnya masih berjalan. Tunggu sebentar.', true); return; }
      if (!ctx.current) { reply(id, 'Pilih dan muat model GGUF terlebih dahulu.', true); return; }
      busy.current = true;
      setStatus('Sedang berpikir…');
      try {
        const prompt = String(args[0] ?? '');
        const nPredict = Math.max(16, Math.min(512, Number(args[1] ?? 128)));
        const result = await ctx.current.completion({
          messages: parsePrompt(prompt),
          n_predict: nPredict,
          temperature: Number(args[2] ?? 0.7),
          top_p: Number(args[3] ?? 0.9),
          penalty_repeat: Number(args[4] ?? 1.1),
          stop: STOP,
          jinja: true,
        });
        reply(id, result.text || 'Model tidak menghasilkan teks.');
        setStatus('Model siap');
      } catch (e: any) {
        reply(id, e?.message || 'Inferensi gagal.', true);
        setStatus('Inferensi gagal');
      } finally { busy.current = false; }
      return;
    }
    if (/unload/i.test(method)) {
      try { await ctx.current?.release(); } catch {}
      ctx.current = null;
      inject('window.__sandevSetModelLoaded(false)');
      setStatus('Model dilepas');
      reply(id, true);
      return;
    }
    if (/model|status/i.test(method)) {
      reply(id, ctx.current ? 'Model siap' : 'Pilih model GGUF');
      return;
    }
    reply(id, '');
  }, [chooseModel, inject, reply]);

  useEffect(() => {
    (async () => {
      try {
        const path = await AsyncStorage.getItem(MODEL_KEY);
        if (path && await RNFS.exists(path)) setStatus('Model tersimpan — pilih untuk memuat ulang');
      } catch {}
      setBooting(false);
    })();
    const back = BackHandler.addEventListener('hardwareBackPress', () => {
      if (web.current) { web.current.goBack(); return true; }
      return false;
    });
    return () => { back.remove(); ctx.current?.release().catch(() => {}); };
  }, []);

  return (
    <SafeAreaView style={{flex: 1, backgroundColor: '#101018'}}>
      <StatusBar barStyle="light-content" backgroundColor="#101018" />
      <View style={{minHeight:26,justifyContent:'center',paddingHorizontal:8,backgroundColor:'#29253a'}}>
        <Text numberOfLines={1} style={{color:'#f2edff',fontSize:11}}>Debug: {status}</Text>
      </View>
      <WebView
        ref={web}
        source={{uri: 'file:///android_asset/sandev/index.html'}}
        originWhitelist={['*']}
        allowFileAccess
        allowFileAccessFromFileURLs
        allowUniversalAccessFromFileURLs
        javaScriptEnabled
        domStorageEnabled
        mixedContentMode="always"
        injectedJavaScriptBeforeContentLoaded={BRIDGE}
        onMessage={onMessage}
        onLoadEnd={() => inject('window.__sandevSetModelLoaded(' + String(!!ctx.current) + ')')}
        style={{flex: 1, backgroundColor: '#101018'}}
      />
      {booting && <View style={{position:'absolute',left:0,right:0,top:0,bottom:0,alignItems:'center',justifyContent:'center',backgroundColor:'#101018'}}><ActivityIndicator size="large" color="#b7a6ff"/><Text style={{color:'#fff',marginTop:12}}>Menyiapkan Sandev Chat…</Text></View>}
    </SafeAreaView>
  );
}
