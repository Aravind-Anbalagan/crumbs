'use strict';

// ── Constants ─────────────────────────────────────────────────
const CONFIDENCE_LEVELS = ['CRITICAL','HIGH','LOW'];
const CONF_DOTS  = { CRITICAL:3, HIGH:2, LOW:1 };
const SR_STYLE   = { CRITICAL:{lineWidth:3,lineStyle:0}, HIGH:{lineWidth:2,lineStyle:0}, LOW:{lineWidth:1,lineStyle:2} };
const SR_COLOR   = {
  support:    { CRITICAL:'#15803d', HIGH:'#0ea5e9', LOW:'#86efac' },
  resistance: { CRITICAL:'#dc2626', HIGH:'#f97316', LOW:'#fca5a5' },
};
const TF_SECS    = { ONE_MINUTE:60, FIVE_MINUTE:300, FIFTEEN_MINUTE:900, ONE_HOUR:3600, ONE_DAY:86400 };
const IST_OFFSET = 19800;
const MAX_RETRY  = 3, RETRY_DELAY = 2000;
const NON_LAYER  = new Set(['candles','signals','supportLevels','resistanceLevels',
  'final_signal','finalSignal','final_confidence','finalConfidence','final_reason','finalReason']);
const LAYER_COLORS = ['#22c55e','#ef4444','#3b82f6','#f59e0b','#a855f7','#06b6d4','#f97316','#ec4899'];

// ── localStorage key ──────────────────────────────────────────
function frozenKey(){ return 'frozenLines_'+selectedSymbol+'_'+selectedTimeFrame; }

// ── State ─────────────────────────────────────────────────────
let chart=null, candleSeries=null, volumeSeries=null;
let selectedSymbol='NIFTY', selectedTimeFrame='FIVE_MINUTE';
let lastCandles=[], lastData=null;
let isFirstLoad=true, chartReady=false;
let refreshTimer=null, retryCount=0;
let drawMode=null, drawnLines=[], drawColor='#22c55e';
let isFrozen=false;
let ssePrevPrice=null;
let srSupLines=[], srResLines=[], srRegistry={};
let layerReg={}, layerColorIdx=0;
let sseSource=null, iKeyCurrent=null;
const iKeyCache={}, pendingTicks=[];

// ── DOM ───────────────────────────────────────────────────────
const $=id=>document.getElementById(id);
const chartEl=$('chart'), loaderEl=$('loader');
const loaderText=$('loaderText'), retryEl=$('retryCount');
const errorEl=$('error'), toastEl=$('apiErrorToast');
const pricePanel=$('pricePanel'), srTooltip=$('srTooltip');
const ppPrice=$('ppPrice'),ppChange=$('ppChange'),ppOpen=$('ppOpen'),ppHigh=$('ppHigh'),ppLow=$('ppLow');
const drawIndicator=$('drawIndicator'), settingsMenu=$('settingsMenu');
const chkSupport=$('chkSupport'), chkResistance=$('chkResistance');
const chkVolume=$('chkVolume'), chkPrice=$('chkPrice'), chkGrid=$('chkGrid'), chkLive=$('chkLive');
const idleScreen=$('idleScreen');

// ── Clock ─────────────────────────────────────────────────────
let clockTimer=null;
function tickClock(){
  $('liveTime').textContent=new Date().toLocaleTimeString('en-IN',
    {timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});
}
function startClock(){if(!clockTimer){tickClock();clockTimer=setInterval(tickClock,1000);}}
function stopClock(){clearInterval(clockTimer);clockTimer=null;}

// ── Freeze helpers ────────────────────────────────────────────
function serializeLines(){
  return drawnLines.map(ld=>{
    if(ld.type==='hline') return {type:'hline', price:ld.price, color:ld.color};
    if(ld.type==='vline') return {type:'vline', time:ld.time, color:ld.color, minP:ld.minP, maxP:ld.maxP};
    return null;
  }).filter(Boolean);
}
function saveLinesToStorage(){
  try{ localStorage.setItem(frozenKey(), JSON.stringify(serializeLines())); }
  catch(e){ console.warn('[Freeze] save failed',e); }
}
function loadLinesFromStorage(){
  try{ const r=localStorage.getItem(frozenKey()); return r?JSON.parse(r):[]; }
  catch(e){ return []; }
}
function clearFrozenStorage(){ try{ localStorage.removeItem(frozenKey()); }catch(e){} }

function restoreFrozenLines(){
  const saved=loadLinesFromStorage();
  if(!saved.length||!candleSeries||!chart)return;
  saved.forEach(ld=>{
    try{
      if(ld.type==='hline'){
        const line=candleSeries.createPriceLine({
          price:ld.price, color:ld.color, lineWidth:2,
          lineStyle:LightweightCharts.LineStyle.Solid, axisLabelVisible:true, title:'❄',
        });
        drawnLines.push({type:'hline', price:ld.price, line, color:ld.color});
      }else if(ld.type==='vline'){
        const vls=chart.addLineSeries({color:ld.color,lineWidth:2,lineStyle:0,
          priceFormat:{type:'price'},lastValueVisible:false,priceLineVisible:false,crosshairMarkerVisible:false});
        vls.setData([{time:ld.time,value:ld.minP},{time:ld.time,value:ld.maxP}]);
        drawnLines.push({type:'vline', time:ld.time, series:vls, color:ld.color, minP:ld.minP, maxP:ld.maxP});
      }
    }catch(e){ console.warn('[Freeze] restore failed',e); }
  });
}
function applyFreezeUI(on){
  isFrozen=on;
  $('btnFreeze').classList.toggle('frozen', on);
  $('btnFreeze').title = on ? 'Unfreeze lines (clear saved)' : 'Freeze lines (save to localStorage)';
  $('freezeBadge').classList.toggle('show', on);
}
function toggleFreeze(){
  if(!isFrozen){
    if(!drawnLines.length){ showToast('No lines to freeze — draw something first'); return; }
    saveLinesToStorage(); applyFreezeUI(true);
    showToast('Lines frozen ❄ — they will reload on Refresh');
  }else{
    clearFrozenStorage(); applyFreezeUI(false);
    showToast('Lines unfrozen — cleared from storage');
  }
}
function syncFreezeState(){
  applyFreezeUI(loadLinesFromStorage().length>0);
}

// ── S/R helpers ───────────────────────────────────────────────
function srReacted(lvl){
  return lvl._type === 'support' ? (lvl.bounce || 0) : (lvl.rejection || 0);
}
function srBroken(lvl){
  return lvl._type === 'support' ? (lvl.breakdown || 0) : (lvl.breakout || 0);
}

// ── S/R drawing ───────────────────────────────────────────────
function getEnabledConf(){
  return new Set(CONFIDENCE_LEVELS.filter(k=>{const el=$('chk'+k);return el&&el.checked;}));
}
function clearLines(arr){
  arr.forEach(l=>{try{candleSeries.removePriceLine(l);}catch(e){}});
  arr.length=0;
}
function srTitle(type, lvl){
  const arrow = type === 'support' ? 'S' : 'R';
  const cmap  = { CRITICAL:'CRT', HIGH:'HI', LOW:'LO' };
  const conf  = cmap[(lvl.confidence || 'LOW').toUpperCase()] || '?';
  const vc    = lvl.heavyVolume ? '*' : '';
  const v     = lvl.visited != null ? `V:${lvl.visited}` : '';
  const r     = srReacted(lvl) > 0  ? `R:${srReacted(lvl)}` : '';
  const b     = srBroken(lvl)  > 0  ? `B:${srBroken(lvl)}`  : '';
  return [arrow, conf, vc, v, r, b].filter(Boolean).join(' ');
}

function drawOneSR(type, lvl, enabledSet){
  if(!candleSeries) return null;
  const conf = (lvl.confidence || 'LOW').toUpperCase();
  if(!enabledSet.has(conf)) return null;
  const st    = SR_STYLE[conf] || SR_STYLE.LOW;
  const color = (SR_COLOR[type] || {})[conf];
  if(!color) return null;
  srRegistry[Number(lvl.price).toFixed(2) + '_' + type] = { ...lvl, _type: type };
  try{
    return candleSeries.createPriceLine({
      price: Number(lvl.price), color, lineWidth: st.lineWidth, lineStyle: st.lineStyle,
      axisLabelVisible: true, title: srTitle(type, lvl),
    });
  }catch(e){ console.warn('[SR]', type, lvl.price, conf, e.message); return null; }
}
function drawSupportLevels(){
  clearLines(srSupLines);
  const levels = (lastData && lastData.supportLevels) || [];
  if(!chkSupport.checked || !candleSeries){ $('supportCount').textContent = ''; return; }
  const enabled = getEnabledConf(); let drawn = 0;
  levels.forEach(lvl => { const l = drawOneSR('support', lvl, enabled); if(l){ srSupLines.push(l); drawn++; } });
  $('supportCount').textContent = levels.length ? drawn + '/' + levels.length : '';
}
function drawResistanceLevels(){
  clearLines(srResLines);
  const levels = (lastData && lastData.resistanceLevels) || [];
  if(!chkResistance.checked || !candleSeries){ $('resistanceCount').textContent = ''; return; }
  const enabled = getEnabledConf(); let drawn = 0;
  levels.forEach(lvl => { const l = drawOneSR('resistance', lvl, enabled); if(l){ srResLines.push(l); drawn++; } });
  $('resistanceCount').textContent = levels.length ? drawn + '/' + levels.length : '';
}
function redrawSR(){ srRegistry = {}; drawSupportLevels(); drawResistanceLevels(); }

// ── Tooltip ───────────────────────────────────────────────────
function findNearestSR(price){
  const threshold = price * 0.003; let best = null, bestDist = Infinity;
  Object.values(srRegistry).forEach(lvl => {
    const d = Math.abs(Number(lvl.price) - price);
    if(d <= threshold && d < bestDist){ bestDist = d; best = lvl; }
  });
  return best;
}
function calcDecision(lvl){
  const r    = srReacted(lvl);
  const b    = srBroken(lvl);
  const conf = (lvl.confidence || '').toUpperCase();
  const isS  = lvl._type === 'support';
  if(conf === 'CRITICAL'){
    if(b === 0) return { cls: 'critical', icon: '🔴', label: isS ? 'Priority Buy Zone'  : 'Priority Sell Zone', detail: `${r} ${isS ? 'bounces' : 'rejections'}, zero ${isS ? 'breakdowns' : 'breakouts'}` };
    return { cls: 'watch', icon: '⚠️', label: 'Critical — breaks recorded', detail: `${b} ${isS ? 'breakdown' : 'breakout'}${b !== 1 ? 's' : ''}` };
  }
  if(conf === 'HIGH'){
    if(b === 0 && r >= 5) return { cls: 'strong', icon: '🛡', label: 'Strong Hold Expected', detail: `${r} ${isS ? 'bounces' : 'rejections'}, no ${isS ? 'breakdowns' : 'breakouts'}` };
    return { cls: 'watch', icon: '⚠️', label: 'Watchlist Level', detail: `V:${lvl.visited || 0} R:${r} B:${b}` };
  }
  return { cls: 'risky', icon: '🟢', label: 'Background Level — Low Priority', detail: `V:${lvl.visited || 0}` };
}

function showSRTooltip(lvl, mx, my){
  const type      = lvl._type;
  const isSupport = type === 'support';
  const conf      = (lvl.confidence || 'LOW').toUpperCase();
  const price    = Number(lvl.price);
  const v        = lvl.visited || 0;
  const vc       = !!lvl.heavyVolume;
  const r = srReacted(lvl);
  const b = srBroken(lvl);
  const typeEl = $('ttType');
  typeEl.textContent = isSupport ? 'Support' : 'Resistance';
  typeEl.className   = 'tt-type ' + type;
  const priceEl = $('ttPrice');
  priceEl.textContent = price.toFixed(2);
  priceEl.className   = 'tt-price ' + type;
  const badge = $('ttBadge');
  badge.textContent = conf;
  badge.className   = 'tt-badge ' + conf;
  const filled = CONF_DOTS[conf] || 1;
  $('ttDots').innerHTML = Array.from({length:3}, (_,i) => `<div class="tt-dot${i < filled ? ' on ' + conf : ''}"></div>`).join('');
  $('ttVisited').textContent = v;
  $('ttReacted').textContent = r;
  const bEl = $('ttBroken');
  bEl.textContent = b;
  bEl.className   = 'tt-stat-val ' + (b === 0 ? 'green' : b <= 2 ? 'amber' : 'red');
  const hvEl = $('ttHeavyVol');
  hvEl.textContent = vc ? 'Yes' : 'No';
  hvEl.className   = 'tt-stat-val ' + (vc ? 'green' : 'red');
  $('ttReactedLbl').textContent = isSupport ? 'Bounce'    : 'Rejection';
  $('ttBrokenLbl').textContent  = isSupport ? 'Breakdown' : 'Breakout';
  const allLvls = Object.values(srRegistry);
  const maxR = Math.max(...allLvls.map(l => srReacted(l)), 1);
  const maxB = Math.max(...allLvls.map(l => srBroken(l)),  1);
  const rejBar = $('ttRejBar');
  rejBar.style.width = Math.min(100, (r / maxR) * 100).toFixed(0) + '%';
  rejBar.className   = 'tt-fill ' + (conf === 'CRITICAL' ? 'critical' : conf === 'HIGH' ? 'high' : 'low');
  $('ttBrkBar').style.width = Math.min(100, (b / maxB) * 100).toFixed(0) + '%';
  $('ttRejNum').textContent = r;
  $('ttBrkNum').textContent = b;
  $('ttRejBarLbl').textContent = isSupport ? 'Bounces'    : 'Rejections';
  $('ttBrkBarLbl').textContent = isSupport ? 'Breakdowns' : 'Breakouts';
  const vcDot = $('ttVcDot');
  vcDot.className = 'tt-vc-dot ' + (vc ? 'on' : 'off');
  const vcTxt = $('ttVcText');
  vcTxt.textContent  = vc ? 'Heavy Volume' : 'No Heavy Volume';
  vcTxt.style.color  = vc ? '#4ade80' : '#64748b';
  $('ttLast').innerHTML = 'Last: <span>' + (lvl.lastVisited || '&mdash;') + '</span>';
  const dec   = calcDecision(lvl);
  const decEl = $('ttDecision');
  decEl.className = 'tt-decision ' + dec.cls;
  decEl.innerHTML = `${dec.icon} <strong>${dec.label}</strong> — ${dec.detail}`;
  const rect = chartEl.getBoundingClientRect();
  const ax = rect.left + mx, ay = rect.top + my, W = 276, H = 340;
  let left = ax + 16, top = ay - H / 2;
  if(left + W > window.innerWidth - 16) left = ax - W - 16;
  top = Math.max(8, Math.min(window.innerHeight - H - 8, top));
  srTooltip.style.left = left + 'px';
  srTooltip.style.top  = top  + 'px';
  srTooltip.classList.add('show');
}
function hideSRTooltip(){ srTooltip.classList.remove('show'); }
function initSRCrosshair(){
  chart.subscribeCrosshairMove(param => {
    if(!param || !param.point || param.point.x == null){ hideSRTooltip(); return; }
    const price = candleSeries.coordinateToPrice(param.point.y);
    if(price == null){ hideSRTooltip(); return; }
    const lvl = findNearestSR(price);
    if(lvl) showSRTooltip(lvl, param.point.x, param.point.y); else hideSRTooltip();
  });
}

// ── Dynamic Layers ────────────────────────────────────────────
function isPriceArray(val){
  if(!Array.isArray(val)||!val.length)return false;
  const f=val[0];
  return typeof f==='number'||(typeof f==='string'&&!isNaN(+f))||(typeof f==='object'&&f!==null&&'level' in f);
}
function getValType(val){
  const f=val[0];
  if(typeof f==='number'||typeof f==='string')return 'number';
  if(typeof f==='object'&&'level' in f)return 'object';
  return null;
}
function toLabel(key){return key.replace(/([A-Z])/g,' $1').replace(/_/g,' ').replace(/\b\w/g,c=>c.toUpperCase()).trim();}
function syncLayers(data){
  let changed=false;
  Object.entries(data).forEach(([key,val])=>{
    if(NON_LAYER.has(key)||!isPriceArray(val)||layerReg[key])return;
    layerReg[key]={label:toLabel(key),color:LAYER_COLORS[layerColorIdx++%LAYER_COLORS.length],enabled:true,lines:[],valType:getValType(val)};
    changed=true;
  });
  if(changed)rebuildLayerUI();
}
function rebuildLayerUI(){
  const container=$('dynamicLayersContainer'),title=$('layerTitle');
  container.innerHTML='';
  const keys=Object.keys(layerReg);
  title.style.display=keys.length?'':'none';
  keys.forEach(key=>{
    const cfg=layerReg[key];
    const lbl=document.createElement('label');
    const cb=document.createElement('input'); cb.type='checkbox'; cb.id='layer_'+key; cb.checked=cfg.enabled;
    cb.addEventListener('change',()=>{layerReg[key].enabled=cb.checked;redrawAllLayers();});
    const dot=document.createElement('span'); dot.className='line-dot'; dot.style.background=cfg.color;
    const txt=document.createElement('span'); txt.textContent=cfg.label;
    lbl.append(cb,dot,txt); container.appendChild(lbl);
  });
}
function clearLayer(key){
  const cfg=layerReg[key]; if(!cfg)return;
  cfg.lines.forEach(l=>{try{candleSeries.removePriceLine(l);}catch(e){}});
  cfg.lines=[];
}
function drawLayer(key,data){
  const cfg=layerReg[key];
  if(!cfg||!cfg.enabled||!candleSeries)return;
  clearLayer(key);
  const raw=data[key]; if(!Array.isArray(raw))return;
  raw.forEach(item=>{
    try{
      const price=cfg.valType==='object'?+item.level:+item;
      const title=cfg.valType==='object'?(item.label||cfg.label):cfg.label;
      if(!isFinite(price))return;
      const line=candleSeries.createPriceLine({price,title,color:cfg.color,lineWidth:1,
        lineStyle:LightweightCharts.LineStyle.Dashed,axisLabelVisible:true});
      cfg.lines.push(line);
    }catch(e){}
  });
}
function redrawAllLayers(){
  if(!lastData||!candleSeries)return;
  Object.keys(layerReg).forEach(k=>{clearLayer(k);if(layerReg[k].enabled)drawLayer(k,lastData);});
  redrawSR(); redrawUserLines();
}

// ── SSE ───────────────────────────────────────────────────────
async function getInstrumentKey(name){
  if(iKeyCache[name])return iKeyCache[name];
  try{
    const res=await fetch('/api/websocket/instrument/key?name='+encodeURIComponent(name));
    const data=await res.json();
    const key=data.key||data.instrumentKey||null;
    if(key)iKeyCache[name]=key;
    return key;
  }catch(e){console.error('[KEY]',name,e);return null;}
}
async function resolveKey(name){
  selectedSymbol=name||$('symbolSelect').value; iKeyCurrent=null;
  const key=await getInstrumentKey(selectedSymbol); iKeyCurrent=key;
  if(pendingTicks.length){pendingTicks.forEach(applyTick);pendingTicks.length=0;}
}
function connectSSE(){
  if(sseSource){sseSource.close();sseSource=null;}
  sseSource=new EventSource('/api/stream/market');
  sseSource.onopen=()=>{$('sseDot').className='live';$('sseLabel').textContent='Live';};
  sseSource.onerror=()=>{
    $('sseDot').className='';$('sseLabel').textContent='Reconnecting…';
    sseSource.close();sseSource=null;setTimeout(connectSSE,3000);
  };
  sseSource.addEventListener('ltp',e=>{
    let d;try{d=JSON.parse(e.data);}catch(ex){return;}
    if(!d||d.price==null)return;
    const price=+d.price,tickKey=d.key?String(d.key):null;
    if(!isFinite(price)||price<=0)return;
    if(tickKey&&!iKeyCurrent){pendingTicks.push({price,tickKey});return;}
    if(tickKey!==iKeyCurrent)return;
    applyTick(price);
  });
}
function applyTick(priceOrObj) {
  const price = Number(typeof priceOrObj === 'object' ? priceOrObj.price : priceOrObj);
  if(isNaN(price) || price <= 0) return;
  updatePricePanel(price); ssePrevPrice = price;
  if(!candleSeries || !lastCandles.length) return;
  const last = lastCandles[lastCandles.length-1];
  const lTime  = last.time; 
  const lOpen  = Number(last.open) || price;
  const lHigh  = Number(last.high) || price;
  const lLow   = Number(last.low) || price;
  const lClose = Number(last.close) || price;
  const lVol   = Number(last.volume) || 0; 
  const tfSec = TF_SECS[selectedTimeFrame] || 300;
  const nowUTC = Math.floor(Date.now() / 1000);
  let barFloor = Math.floor((nowUTC + IST_OFFSET) / tfSec) * tfSec - IST_OFFSET;
  let isNewCandle = false;
  if (selectedTimeFrame === 'ONE_DAY') {
    const d = new Date((nowUTC + IST_OFFSET) * 1000);
    const yy = d.getUTCFullYear();
    const mo = String(d.getUTCMonth() + 1).padStart(2, '0');
    const dd = String(d.getUTCDate()).padStart(2, '0');
    barFloor = `${yy}-${mo}-${dd}`; 
    isNewCandle = (barFloor !== lTime);
  } else {
    isNewCandle = (barFloor > Number(lTime));
  }
  if(isNewCandle){
    const nc = {time: barFloor, open: price, high: price, low: price, close: price, volume: 0};
    try {
      candleSeries.update(nc);
      volumeSeries.update({time: barFloor, value: 0, color: 'rgba(38,166,154,.5)'});
      lastCandles.push(nc);
    } catch(e) { console.warn("Tick new candle error:", e); }
    return;
  }
  const upd = { time: lTime, open: lOpen, high: Math.max(lHigh, price), low: Math.min(lLow, price), close: price, volume: lVol };
  if(upd.close === lClose && upd.high === lHigh && upd.low === lLow) return;
  try {
    candleSeries.update(upd);
    volumeSeries.update({ time: upd.time, value: upd.volume, color: upd.close >= upd.open ? 'rgba(38,166,154,.5)' : 'rgba(239,83,80,.5)' });
    lastCandles[lastCandles.length-1] = upd;
  } catch(e) { console.warn("Tick update error:", e); }
}

// ── Price Panel ───────────────────────────────────────────────
function todayStart(){
  const now=new Date(),ist=new Date(now.toLocaleString('en-US',{timeZone:'Asia/Kolkata'}));
  return Math.floor(new Date(ist.getFullYear(),ist.getMonth(),ist.getDate()).getTime()/1000);
}
function updatePricePanel(price){
  if(!chkPrice.checked){pricePanel.classList.remove('show');return;}
  pricePanel.classList.add('show');
  if(ssePrevPrice!==null&&price!==ssePrevPrice){ppPrice.classList.remove('flash');void ppPrice.offsetWidth;ppPrice.classList.add('flash');}
  ppPrice.textContent=price.toFixed(2);
  ppPrice.className='pp-price '+(ssePrevPrice===null||price>=ssePrevPrice?'up':'down');
  if(!lastCandles.length)return;
  const ts=todayStart(),today=lastCandles.filter(c=>c.time>=ts);
  if(!today.length)return;
  const open=+today[0].open;
  const high=Math.max(...today.map(c=>+c.high),price);
  const low =Math.min(...today.map(c=>+c.low), price);
  const change=price-open,pct=((change/open)*100).toFixed(2);
  ppChange.innerHTML=(change>=0?'▲':'▼')+' '+Math.abs(change).toFixed(2)+' ('+Math.abs(pct)+'%)';
  ppChange.className='pp-change '+(change>=0?'up':'down');
  ppOpen.textContent=open.toFixed(2);ppHigh.textContent=high.toFixed(2);ppLow.textContent=low.toFixed(2);
}
function refreshPricePanel(){
  if(!lastCandles.length)return;
  const cp=ssePrevPrice!==null?ssePrevPrice:+lastCandles[lastCandles.length-1].close;
  updatePricePanel(cp);
}

// ── Chart ─────────────────────────────────────────────────────
function initChart(){
  if(chart) return;
  const w = chartEl.clientWidth || (window.innerWidth - 32);
  const h = chartEl.clientHeight || (window.innerHeight - 32);
  chart = LightweightCharts.createChart(chartEl, {
    width: w, height: h,
    layout: { backgroundColor: '#fff', textColor: '#000' },
    rightPriceScale: { borderVisible: false, scaleMargins: { top: .1, bottom: .2 } },
    timeScale: {
      timeVisible: true, rightOffset: 30, barSpacing: 6, minBarSpacing: 3, borderColor: '#d1d4dc',
      tickMarkFormatter: (time, tickMarkType) => {
        const d = new Date((Number(time) + IST_OFFSET) * 1000);
        const hh = String(d.getUTCHours()).padStart(2, '0');
        const mm = String(d.getUTCMinutes()).padStart(2, '0');
        if (tickMarkType === LightweightCharts.TickMarkType.Year || tickMarkType === LightweightCharts.TickMarkType.Month || tickMarkType === LightweightCharts.TickMarkType.DayOfMonth) {
          const dd = String(d.getUTCDate()).padStart(2, '0');
          const mo = String(d.getUTCMonth() + 1).padStart(2, '0');
          return `${dd}/${mo}`;
        }
        return `${hh}:${mm}`;
      }
    },
    crosshair: {
      mode: LightweightCharts.CrosshairMode.Normal,
      vertLine: { width: 1, color: '#758696', style: LightweightCharts.LineStyle.Dashed, labelBackgroundColor: '#4a5568' },
      horzLine: { width: 1, color: '#758696', style: LightweightCharts.LineStyle.Dashed, labelBackgroundColor: '#4a5568' },
    },
    grid: { vertLines: { visible: chkGrid.checked, color: '#eee' }, horzLines: { visible: chkGrid.checked, color: '#eee' } },
    localization: {
      timeFormatter(ts) {
        const d = new Date((Number(ts) + IST_OFFSET) * 1000);
        const dd = String(d.getUTCDate()).padStart(2, '0');
        const mo = String(d.getUTCMonth() + 1).padStart(2, '0');
        const yy = d.getUTCFullYear();
        const hh = String(d.getUTCHours()).padStart(2, '0');
        const mm = String(d.getUTCMinutes()).padStart(2, '0');
        return `${dd}/${mo}/${yy} ${hh}:${mm}`;
      }
    }
  });
  candleSeries = chart.addCandlestickSeries({ upColor: '#26a69a', downColor: '#ef5350', borderVisible: false, wickUpColor: '#26a69a', wickDownColor: '#ef5350' });
  volumeSeries = chart.addHistogramSeries({ color: 'rgba(38,166,154,.5)', priceFormat: { type: 'volume' }, priceScaleId: 'volume', scaleMargins: { top: .9, bottom: 0 }, overlay: true, lineWidth: 1, visible: chkVolume.checked });
  chartEl.addEventListener('click', onChartClick);
  initSRCrosshair();
  chartReady = true;
}
function destroyChart(){
  hideSRTooltip(); srRegistry={};
  drawnLines.forEach(ld=>{try{if(ld.type==='hline')candleSeries.removePriceLine(ld.line);else chart.removeSeries(ld.series);}catch(e){}});
  drawnLines=[]; clearLines(srSupLines); clearLines(srResLines);
  Object.values(layerReg).forEach(cfg=>{cfg.lines.forEach(l=>{try{candleSeries.removePriceLine(l);}catch(e){}});cfg.lines=[];});
  if(chart){
    if(candleSeries){try{chart.removeSeries(candleSeries);}catch(e){} candleSeries=null;}
    if(volumeSeries){try{chart.removeSeries(volumeSeries);}catch(e){} volumeSeries=null;}
    try{chart.remove();}catch(e){} chart=null;
  }
  chartReady=false;
}
function vc(c) { return Number(c.close) >= Number(c.open) ? 'rgba(38,166,154,.5)' : 'rgba(239,83,80,.5)'; }
function updateChart(data){
  if(!data||!data.candles||!data.candles.length)return;
  lastData=data;
  const seenTimes = new Set(); const candles = [];
  for (const c of data.candles) {
    let t = Number(c.time);
    if (t > 10000000000) { t = Math.floor(t / 1000); }
    if (isNaN(t) || seenTimes.has(t)) continue;
    seenTimes.add(t);
    let finalTime = t;
    if (selectedTimeFrame === 'ONE_DAY') {
       const d = new Date((t + IST_OFFSET) * 1000);
       const yy = d.getUTCFullYear();
       const mo = String(d.getUTCMonth() + 1).padStart(2, '0');
       const dd = String(d.getUTCDate()).padStart(2, '0');
       finalTime = `${yy}-${mo}-${dd}`;
    }
    candles.push({ time: finalTime, open: Number(c.open), high: Number(c.high), low: Number(c.low), close: Number(c.close), volume: Number(c.volume) || 0 });
  }
  candles.sort((a, b) => (typeof a.time === 'string' ? a.time.localeCompare(b.time) : a.time - b.time));
  syncLayers(data);
  if(isFirstLoad){
    candleSeries.setData(candles);
    volumeSeries.setData(candles.map(c=>({time:c.time,value:c.volume,color:vc(c)})));
    lastCandles=candles.slice();
    restoreFrozenLines(); redrawAllLayers(); refreshPricePanel();
    chart.timeScale().fitContent();
    isFirstLoad=false; return;
  }
  const lastT=lastCandles.length?lastCandles[lastCandles.length-1].time:0;
  if(candles.length<lastCandles.length){
    candleSeries.setData(candles);
    volumeSeries.setData(candles.map(c=>({time:c.time,value:c.volume,color:vc(c)})));
    lastCandles=candles.slice(); setTimeout(redrawAllLayers,50);
  }else{
    const idx=candles.findIndex(c=>c.time===lastT);
    const missing=(idx>=0&&idx<candles.length-1)?candles.slice(idx+1):[];
    if(missing.length>2){
      candleSeries.setData(candles);
      volumeSeries.setData(candles.map(c=>({time:c.time,value:c.volume,color:vc(c)})));
      lastCandles=candles.slice(); setTimeout(redrawAllLayers,50);
    }else if(missing.length>0){
      missing.forEach(c=>{candleSeries.update(c);volumeSeries.update({time:c.time,value:c.volume,color:vc(c)});});
      lastCandles=candles.slice();
    }else{
      const nl=candles[candles.length-1],ol=lastCandles[lastCandles.length-1];
      if(ol.time===nl.time){
        if(ol.close!==nl.close||ol.high!==nl.high||ol.low!==nl.low||ol.volume!==nl.volume){
          candleSeries.update(nl); volumeSeries.update({time:nl.time,value:nl.volume,color:vc(nl)});
          lastCandles[lastCandles.length-1]=nl;
        }
      }else if(+nl.time>+ol.time){
        candleSeries.update(nl);volumeSeries.update({time:nl.time,value:nl.volume,color:vc(nl)});
        lastCandles=candles.slice();
      }
    }
    setTimeout(redrawAllLayers,50);
  }
  refreshPricePanel();
}

// ── Fetch ─────────────────────────────────────────────────────
async function fetchData(){
  const r=await fetch('/api/intraday?timeFrame='+selectedTimeFrame+'&name='+selectedSymbol);
  if(!r.ok)throw new Error('HTTP '+r.status);
  return r.json();
}
function showToast(msg){
  toastEl.textContent='⚠️ '+msg;
  toastEl.classList.remove('fade-out');toastEl.classList.add('show');
  setTimeout(()=>{toastEl.classList.add('fade-out');setTimeout(()=>toastEl.classList.remove('show','fade-out'),500);},3000);
}
async function refresh(firstLoad){
  if(idleScreen) idleScreen.style.display='none';
  if(firstLoad){
    loaderEl.style.display='flex'; chartEl.style.display='none'; errorEl.style.display='none';
    retryCount=0; loaderText.textContent='Loading chart data…'; retryEl.textContent='';
  }
  try{
    const data=await fetchData();
    chartEl.style.display='block'; 
    if(!chart)initChart();
    updateChart(data);
    loaderEl.style.display='none'; errorEl.style.display='none'; retryCount=0;
    if(firstLoad)setTimeout(()=>{if(chart)chart.resize(chartEl.clientWidth,chartEl.clientHeight);},50);
  }catch(e){
    if(firstLoad&&retryCount<MAX_RETRY){
      retryCount++;loaderText.textContent='Connection failed. Retrying…';retryEl.textContent='Attempt '+retryCount+' of '+MAX_RETRY;
      setTimeout(()=>refresh(true),RETRY_DELAY);
    }else if(firstLoad){
      loaderEl.style.display='none';chartEl.style.display='none';
      if(idleScreen){
        idleScreen.style.display='flex';
        idleScreen.querySelector('.idle-msg').textContent='Load failed';
        idleScreen.querySelector('.idle-sub').textContent=e.message+' — press Refresh to retry';
      }
      errorEl.textContent='Failed after '+MAX_RETRY+' attempts: '+e.message;errorEl.style.display='block';retryCount=0;
    }else{ showToast('API Error – Retrying…'); }
  }
}
function setLiveRefresh(on){
  clearInterval(refreshTimer);refreshTimer=null;
  if(on&&chartReady)refreshTimer=setInterval(()=>refresh(false),30000);
}

// ── Drawing ───────────────────────────────────────────────────
function setDrawMode(mode){
  drawMode=drawMode===mode?null:mode;
  $('btnHLine').classList.toggle('active',drawMode==='hline');
  $('btnVLine').classList.toggle('active',drawMode==='vline');
  chartEl.classList.toggle('drawing-mode',drawMode!==null);
  if(drawMode){
    drawIndicator.style.display='block';
    drawIndicator.textContent=drawMode==='hline'?'Click to draw horizontal line':'Click to draw vertical line';
  }else drawIndicator.style.display='none';
}
function onChartClick(e){
  if(!drawMode||!chart)return;
  const rect=chartEl.getBoundingClientRect(),x=e.clientX-rect.left,y=e.clientY-rect.top;
  if(drawMode==='hline'){
    const price=candleSeries.coordinateToPrice(y);
    if(price!=null){
      const line=candleSeries.createPriceLine({price,color:drawColor,lineWidth:2,lineStyle:LightweightCharts.LineStyle.Solid,axisLabelVisible:true,title:'—'});
      drawnLines.push({type:'hline',price,line,color:drawColor});
      if(isFrozen) saveLinesToStorage();
    }
  }else if(drawMode==='vline'){
    const time=chart.timeScale().coordinateToTime(x);
    if(time&&lastCandles.length){
      const lows=lastCandles.map(c=>+c.low),highs=lastCandles.map(c=>+c.high);
      const pad=(Math.max(...highs)-Math.min(...lows))*0.1;
      const minP=Math.min(...lows)-pad,maxP=Math.max(...highs)+pad;
      const vls=chart.addLineSeries({color:drawColor,lineWidth:2,lineStyle:0,priceFormat:{type:'price'},lastValueVisible:false,priceLineVisible:false,crosshairMarkerVisible:false});
      vls.setData([{time,value:minP},{time,value:maxP}]);
      drawnLines.push({type:'vline',time,series:vls,color:drawColor,minP,maxP});
      if(isFrozen) saveLinesToStorage();
    }
  }
  setDrawMode(null);
}
function redrawUserLines(){
  if(!candleSeries||!chart)return;
  const valid=[];
  drawnLines.forEach(ld=>{
    try{
      if(ld.type==='hline'){
        if(ld.line){try{candleSeries.removePriceLine(ld.line);}catch(e){}}
        const nl=candleSeries.createPriceLine({price:ld.price,color:ld.color,lineWidth:2,lineStyle:LightweightCharts.LineStyle.Solid,axisLabelVisible:true,title:isFrozen?'❄':'—'});
        valid.push({type:'hline',price:ld.price,line:nl,color:ld.color});
      }else if(ld.type==='vline'){
        if(ld.series){try{chart.removeSeries(ld.series);}catch(e){}}
        const vls=chart.addLineSeries({color:ld.color,lineWidth:2,lineStyle:0,priceFormat:{type:'price'},lastValueVisible:false,priceLineVisible:false,crosshairMarkerVisible:false});
        vls.setData([{time:ld.time,value:ld.minP},{time:ld.time,value:ld.maxP}]);
        valid.push({type:'vline',time:ld.time,series:vls,color:ld.color,minP:ld.minP,maxP:ld.maxP});
      }
    }catch(e){}
  });
  drawnLines=valid;
}

// ── Today Zoom ────────────────────────────────────────────────
function zoomToToday(){
  if(!chart||!lastCandles.length)return;
  const ts=todayStart(),today=lastCandles.filter(c=>c.time>=ts);
  if(!today.length){chart.timeScale().fitContent();return;}
  const lastT=today[today.length-1].time,tfSec=TF_SECS[selectedTimeFrame]||300;
  const bars=selectedTimeFrame==='ONE_MINUTE'?60:selectedTimeFrame==='FIFTEEN_MINUTE'?40:50;
  try{chart.timeScale().setVisibleRange({from:Math.max(ts,lastT-bars*tfSec*0.6),to:lastT+bars*tfSec*0.4});}
  catch(e){chart.timeScale().fitContent();}
}

// ── Event wiring ──────────────────────────────────────────────
chkVolume.addEventListener('change',()=>{if(volumeSeries)volumeSeries.applyOptions({visible:chkVolume.checked});});
chkPrice.addEventListener('change', ()=>refreshPricePanel());
chkGrid.addEventListener('change',  ()=>{if(chart)chart.applyOptions({grid:{vertLines:{visible:chkGrid.checked,color:'#eee'},horzLines:{visible:chkGrid.checked,color:'#eee'}}});});
chkLive.addEventListener('change',  ()=>setLiveRefresh(chkLive.checked));
chkSupport.addEventListener('change',    ()=>redrawSR());
chkResistance.addEventListener('change', ()=>redrawSR());
CONFIDENCE_LEVELS.forEach(k=>{const el=$('chk'+k);if(el)el.addEventListener('change',()=>redrawSR());});

const SR_CHKS=['chkSupport','chkResistance',...CONFIDENCE_LEVELS.map(k=>'chk'+k)];
const ALL_CHKS=['chkVolume','chkPrice','chkGrid','chkLive',...SR_CHKS];

$('btnReset').addEventListener('click',()=>{
  ALL_CHKS.forEach(id=>{const el=$(id);if(el)el.checked=true;});
  Object.values(layerReg).forEach(cfg=>cfg.enabled=true);rebuildLayerUI();
  if(chart)chart.applyOptions({grid:{vertLines:{visible:true,color:'#eee'},horzLines:{visible:true,color:'#eee'}}});
  if(volumeSeries)volumeSeries.applyOptions({visible:true});
  redrawAllLayers();refreshPricePanel();setLiveRefresh(true);
});
$('btnUncheckAll').addEventListener('click',()=>{
  ALL_CHKS.forEach(id=>{const el=$(id);if(el)el.checked=false;});
  Object.values(layerReg).forEach(cfg=>cfg.enabled=false);rebuildLayerUI();
  if(chart)chart.applyOptions({grid:{vertLines:{visible:false,color:'#eee'},horzLines:{visible:false,color:'#eee'}}});
  if(volumeSeries)volumeSeries.applyOptions({visible:false});
  redrawAllLayers();pricePanel.classList.remove('show');setLiveRefresh(false);
});
$('btnFit').addEventListener('click',()=>{if(chart)chart.timeScale().fitContent();});
$('symbolSelect').addEventListener('change',()=>{
  selectedSymbol=$('symbolSelect').value;ssePrevPrice=null;iKeyCurrent=null;pendingTicks.length=0;
  layerReg={};layerColorIdx=0;resolveKey(selectedSymbol); syncFreezeState();
  destroyChart();isFirstLoad=true;lastCandles=[];refresh(true);
});
$('tfSelect').addEventListener('change',()=>{
  selectedTimeFrame=$('tfSelect').value; syncFreezeState();
  destroyChart();isFirstLoad=true;lastCandles=[];refresh(true);
});
$('btnHLine').addEventListener('click',    ()=>setDrawMode('hline'));
$('btnVLine').addEventListener('click',    ()=>setDrawMode('vline'));
$('colorPicker').addEventListener('change',e=>drawColor=e.target.value);
$('btnFreeze').addEventListener('click',   ()=>toggleFreeze());
$('btnClearDraw').addEventListener('click',()=>{
  drawnLines.forEach(ld=>{try{if(ld.type==='hline')candleSeries.removePriceLine(ld.line);else chart.removeSeries(ld.series);}catch(e){}});
  drawnLines=[];setDrawMode(null); if(isFrozen) saveLinesToStorage();
});
$('btnToday').addEventListener('click',()=>zoomToToday());
$('btnRefresh').addEventListener('click',()=>{
  clearInterval(refreshTimer);refreshTimer=null; isFirstLoad=true;retryCount=0;
  errorEl.style.display='none';loaderEl.style.display='flex';chartEl.style.display='none';
  loaderText.textContent='Loading chart data…';retryEl.textContent='';
  destroyChart();chartReady=false; refresh(true).then(()=>setLiveRefresh(chkLive.checked)).catch(()=>{});
});
$('btnSettings').addEventListener('click',e=>{e.stopPropagation();settingsMenu.classList.toggle('open');});
document.addEventListener('click',e=>{if(!$('settingsDropdown').contains(e.target))settingsMenu.classList.remove('open');});
window.addEventListener('resize',()=>{if(chart)chart.resize(chartEl.clientWidth,chartEl.clientHeight);});
document.addEventListener('visibilitychange',()=>{
  if(document.hidden){stopClock();clearInterval(refreshTimer);refreshTimer=null;}
  else{
    startClock();connectSSE();
    if(chkLive.checked&&chartReady){refresh(false);setLiveRefresh(true);}
    if(chart)setTimeout(()=>chart.resize(chartEl.clientWidth,chartEl.clientHeight),100);
  }
});

// ── Boot ──────────────────────────────────────────────────────
window.addEventListener('load',()=>{
  startClock();
  selectedSymbol    = $('symbolSelect').value;
  selectedTimeFrame = $('tfSelect').value;
  resolveKey(selectedSymbol).then(connectSSE);
  syncFreezeState();
});