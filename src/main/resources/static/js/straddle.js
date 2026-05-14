const API_URL = '/api/straddle/combined-chart';
const GROUPED_API_URL = '/api/straddle/grouped';
const API_PARAMS = { name: 'NIFTY', expiry: '16DEC25', ceStrike: '26000', peStrike: '26000' };
const STORAGE_KEY_LINES = 'nifty_lines_visibility_v2';

let groupedData = [];
let apiData = [];
let currentSeries = {
    ceSeries: [], peSeries: [], combinedPremiumSeries: [],
    ceVwapSeries: [], peVwapSeries: [], combinedVwapSeries: [],
    combinedPrevLowSeries: []
};

// Start in Dark Theme by default to match the HTML layout
let isDarkTheme = true;
let autoRefreshInterval = null;
let autoUpdateEnabled = true;
let isInitialLoad = true;
let savedVisibleRange = null;
let lastUpdateTimestamp = null;
let crosshairUpdatePending = false;

// Default 7 lines
let lineVisibility = {
    ce: true, pe: true, combinedPremium: true,
    ceVwap: true, peVwap: true, combinedVwap: true, combinedPrevLow: true
};

let pendingLineVisibility = {...lineVisibility};

function saveLineVisibility() {
    try { localStorage.setItem(STORAGE_KEY_LINES, JSON.stringify(lineVisibility)); } catch (e) {}
}

function loadLineVisibility() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY_LINES);
        if (!raw) return;
        const parsed = JSON.parse(raw);
        Object.keys(lineVisibility).forEach(k => {
            if (Object.prototype.hasOwnProperty.call(parsed, k)) lineVisibility[k] = !!parsed[k];
        });
    } catch (e) {}
}

function syncCheckboxesFromVisibility() {
    const map = {
        ce: 'ce-check', pe: 'pe-check', combinedPremium: 'combined-premium-check',
        ceVwap: 'ce-vwap-check', peVwap: 'pe-vwap-check', combinedVwap: 'combined-vwap-check',
        combinedPrevLow: 'combined-prev-low-check'
    };
    Object.entries(map).forEach(([key, id]) => {
        const el = document.getElementById(id);
        if (el) el.checked = !!lineVisibility[key];
    });
    updateAllCheckbox();
}

function updateAllCheckbox() {
    const vals = Object.values(pendingLineVisibility);
    const allBox = document.getElementById('all-lines-check');
    if (!allBox) return;
    allBox.indeterminate = !vals.every(Boolean) && !vals.every(v => !v);
    allBox.checked = vals.every(Boolean);
}

function setAllLinesChecked(checked) {
    Object.keys(pendingLineVisibility).forEach(k => pendingLineVisibility[k] = checked);
    document.getElementById('ce-check').checked = checked;
    document.getElementById('pe-check').checked = checked;
    document.getElementById('combined-premium-check').checked = checked;
    document.getElementById('ce-vwap-check').checked = checked;
    document.getElementById('pe-vwap-check').checked = checked;
    document.getElementById('combined-vwap-check').checked = checked;
    document.getElementById('combined-prev-low-check').checked = checked;
    updateAllCheckbox();
}

function applyLineVisibilityChanges() {
    lineVisibility = {...pendingLineVisibility};
    lines.ce.setData(lineVisibility.ce ? currentSeries.ceSeries : []);
    lines.pe.setData(lineVisibility.pe ? currentSeries.peSeries : []);
    lines.combinedPremium.setData(lineVisibility.combinedPremium ? currentSeries.combinedPremiumSeries : []);
    lines.ceVwap.setData(lineVisibility.ceVwap ? currentSeries.ceVwapSeries : []);
    lines.peVwap.setData(lineVisibility.peVwap ? currentSeries.peVwapSeries : []);
    lines.combinedVwap.setData(lineVisibility.combinedVwap ? currentSeries.combinedVwapSeries : []);
    lines.combinedPrevLow.setData(lineVisibility.combinedPrevLow ? currentSeries.combinedPrevLowSeries : []);
    saveLineVisibility();
}

// High-speed string parsing to prevent DOM freezing on large datasets
function toUnixSeconds(isoString) {
    const year = parseInt(isoString.substring(0, 4), 10);
    const month = parseInt(isoString.substring(5, 7), 10) - 1;
    const day = parseInt(isoString.substring(8, 10), 10);
    const hour = parseInt(isoString.substring(11, 13), 10);
    const minute = parseInt(isoString.substring(14, 16), 10);
    const second = parseInt(isoString.substring(17, 19), 10);
    return Math.floor(Date.UTC(year, month, day, hour, minute, second) / 1000);
}

function showError(message) {
    console.error(message);
    document.getElementById('status-indicator').className = 'status-indicator error';
}

function updateLastUpdateTime() {
    document.getElementById('last-update-time').textContent = new Date().toLocaleTimeString('en-IN', {
        timeZone: 'Asia/Kolkata', hour12: false
    });
}

function transformToSeries(apiArray) {
    const series = { ce: [], pe: [], combinedPremium: [], ceVwap: [], peVwap: [], combinedVwap: [], combinedPrevLow: [] };

    for (const row of apiArray) {
        const t = toUnixSeconds(row.timestamp);
        if (!isFinite(t)) continue;

        if (Number.isFinite(Number(row.ce))) series.ce.push({ time: t, value: Number(row.ce) });
        if (Number.isFinite(Number(row.pe))) series.pe.push({ time: t, value: Number(row.pe) });
        if (row.combinedPremium != null) series.combinedPremium.push({ time: t, value: Number(row.combinedPremium) });
        if (row.ceVwap != null) series.ceVwap.push({ time: t, value: Number(row.ceVwap) });
        if (row.peVwap != null) series.peVwap.push({ time: t, value: Number(row.peVwap) });
        if (row.combinedVwap != null) series.combinedVwap.push({ time: t, value: Number(row.combinedVwap) });
        if (row.combinedPrevLow != null) series.combinedPrevLow.push({ time: t, value: Number(row.combinedPrevLow) });
    }

    Object.assign(currentSeries, {
        ceSeries: series.ce, peSeries: series.pe, combinedPremiumSeries: series.combinedPremium,
        ceVwapSeries: series.ceVwap, peVwapSeries: series.peVwap, combinedVwapSeries: series.combinedVwap,
        combinedPrevLowSeries: series.combinedPrevLow
    });
}

// Initialize Chart with the v4.1 API for background color
const chart = LightweightCharts.createChart(document.getElementById('chart'), {
    layout: { 
        background: { type: 'solid', color: '#071421' }, 
        textColor: '#dbeafe' 
    },
    rightPriceScale: { borderVisible: false, visible: true },
    leftPriceScale: { borderVisible: false, visible: false },
    timeScale: {
        borderVisible: false, timeVisible: true, secondsVisible: true,
        tickMarkFormatter: (time) => {
            const date = new Date(time * 1000);
            return `${String(date.getUTCHours()).padStart(2, '0')}:${String(date.getUTCMinutes()).padStart(2, '0')}:${String(date.getUTCSeconds()).padStart(2, '0')}`;
        }
    },
    grid: { vertLines: { visible: false }, horzLines: { color: 'rgba(255,255,255,0.03)' } },
    crosshair: { mode: LightweightCharts.CrosshairMode.Normal }
});

const lines = {
    ce: chart.addLineSeries({ priceLineVisible: false, lastValueVisible: true, lineWidth: 3, color: '#22c55e', priceScaleId: 'right' }),
    pe: chart.addLineSeries({ priceLineVisible: false, lastValueVisible: true, lineWidth: 3, color: '#ef4444', priceScaleId: 'right' }),
    combinedPremium: chart.addLineSeries({ priceLineVisible: false, lastValueVisible: true, lineWidth: 2.5, color: '#3b82f6', priceScaleId: 'right', title: 'Comb Price' }),
    ceVwap: chart.addLineSeries({ priceLineVisible: false, lastValueVisible: true, lineWidth: 2.5, color: '#a78bfa', priceScaleId: 'right' }),
    peVwap: chart.addLineSeries({ priceLineVisible: false, lastValueVisible: true, lineWidth: 2.5, color: '#fb923c', priceScaleId: 'right' }),
    combinedVwap: chart.addLineSeries({ priceLineVisible: false, lastValueVisible: true, lineWidth: 2.5, color: '#06b6d4', priceScaleId: 'right', title: 'Comb Vwap' }),
    combinedPrevLow: chart.addLineSeries({ lineWidth: 2, color: '#581c87', lineStyle: 1, priceScaleId: 'right', title: 'Comb Prev Low' })
};

// Toggle Theme Logic
function updateChartTheme() {
    chart.applyOptions({
        layout: { 
            background: { type: 'solid', color: isDarkTheme ? '#071421' : '#ffffff' }, 
            textColor: isDarkTheme ? '#dbeafe' : '#1e293b' 
        },
        grid: { 
            vertLines: { visible: false }, 
            horzLines: { color: isDarkTheme ? 'rgba(255,255,255,0.03)' : '#f1f5f9' } 
        }
    });
}

function toggleTheme() {
    isDarkTheme = !isDarkTheme;
    document.body.style.background = isDarkTheme ? "#071421" : "#f8fafc";
    document.body.style.color = isDarkTheme ? "#dbeafe" : "#1e293b";
    
    document.getElementById('theme-switch').classList.toggle('active', isDarkTheme);
    document.querySelector('#theme-toggle .toggle-label').textContent = isDarkTheme ? 'Dark' : 'Light';
    updateChartTheme();
}

// DOM Cache for high-performance legend updates
const domCache = {};
function initDOMCache() {
    ['diff', 'ce', 'pe', 'comb', 'ce-vwap', 'pe-vwap', 'comb-vwap', 'comb-low'].forEach(id => {
        domCache[id] = document.getElementById(`val-${id}`);
    });
}

function updateLegendValues(vals) {
    const fmt = (v) => v !== null && Number.isFinite(v) ? v.toFixed(2) : '-';
    
    // Custom Logic: Difference between Combined Premium and Combined VWAP
    if (vals.comb !== null && vals['comb-vwap'] !== null) {
        const diffVal = vals.comb - vals['comb-vwap'];
        const sign = diffVal > 0 ? '+' : '';
        domCache.diff.textContent = `${sign}${diffVal.toFixed(2)}`;
        domCache.diff.style.color = diffVal >= 0 ? '#22c55e' : '#ef4444'; 
    } else {
        domCache.diff.textContent = '-';
        domCache.diff.style.color = '#94a3b8';
    }

    domCache['ce'].textContent = fmt(vals.ce);
    domCache['pe'].textContent = fmt(vals.pe);
    domCache['comb'].textContent = fmt(vals.comb);
    domCache['ce-vwap'].textContent = fmt(vals['ce-vwap']);
    domCache['pe-vwap'].textContent = fmt(vals['pe-vwap']);
    domCache['comb-vwap'].textContent = fmt(vals['comb-vwap']);
    domCache['comb-low'].textContent = fmt(vals['comb-low']);
}

function updateLegendLatest() {
    const getLast = (series) => (series && series.length > 0) ? series[series.length - 1].value : null;
    updateLegendValues({
        ce: getLast(currentSeries.ceSeries),
        pe: getLast(currentSeries.peSeries),
        comb: getLast(currentSeries.combinedPremiumSeries),
        'ce-vwap': getLast(currentSeries.ceVwapSeries),
        'pe-vwap': getLast(currentSeries.peVwapSeries),
        'comb-vwap': getLast(currentSeries.combinedVwapSeries),
        'comb-low': getLast(currentSeries.combinedPrevLowSeries)
    });
}

function updateLegendAtCrosshair(param) {
    if (!param || !param.time || !param.point || param.point.x < 0 || param.point.y < 0) {
        updateLegendLatest(); // Snap back to live price when mouse leaves chart
        return;
    }

    const getData = (line) => param.seriesData.get(line);
    const getValue = (data) => data && data.value !== undefined ? data.value : null;

    updateLegendValues({
        ce: getValue(getData(lines.ce)),
        pe: getValue(getData(lines.pe)),
        comb: getValue(getData(lines.combinedPremium)),
        'ce-vwap': getValue(getData(lines.ceVwap)),
        'pe-vwap': getValue(getData(lines.peVwap)),
        'comb-vwap': getValue(getData(lines.combinedVwap)),
        'comb-low': getValue(getData(lines.combinedPrevLow))
    });
}

// Loader Logic
function showLoader() {
    document.getElementById('loading-overlay').style.display = 'flex';
    Object.keys(lines).forEach(k => lines[k].setData([])); // Clear old lines instantly
    updateLegendValues({ ce: null, pe: null, comb: null, 'ce-vwap': null, 'pe-vwap': null, 'comb-vwap': null, 'comb-low': null });
}

function hideLoader() {
    document.getElementById('loading-overlay').style.display = 'none';
}

async function fetchDataFromAPI() {
    try {
        const response = await fetch(`${API_URL}?${new URLSearchParams(API_PARAMS)}`);
        if (!response.ok) throw new Error(`API failed: ${response.status}`);
        const result = await response.json();
        document.getElementById('status-indicator').className = 'status-indicator live';
        updateLastUpdateTime();
        return result.data || [];
    } catch (err) {
        showError('Error fetching data.');
        return [];
    }
}

async function loadGroupedData() {
    try {
        const res = await fetch(GROUPED_API_URL);
        if (!res.ok) throw new Error('API failed');
        groupedData = await res.json();
        populateNameSelect();
    } catch (err) {}
}

function populateNameSelect() {
    const sel = document.getElementById('name-select');
    sel.innerHTML = groupedData.map(item => `<option value="${item.name}">${item.name}</option>`).join('');
    API_PARAMS.name = sel.value;
    populateExpirySelect();
}

function populateExpirySelect() {
    const nameSel = document.getElementById('name-select');
    const expirySel = document.getElementById('expiry-select');
    const selected = groupedData.find(x => x.name === nameSel.value);
    
    expirySel.innerHTML = (selected ? Object.keys(selected.expiries) : []).map(exp => `<option value="${exp}">${exp}</option>`).join('');
    API_PARAMS.expiry = expirySel.value;
    populateStrikeSelects();
}

function populateStrikeSelects() {
    const nameSel = document.getElementById('name-select');
    const selected = groupedData.find(x => x.name === nameSel.value);
    const strikes = selected ? (selected.expiries[document.getElementById('expiry-select').value] || []) : [];
    
    const strikeOptions = strikes.map(s => `<option value="${s}">${s}</option>`).join('');
    const ceSel = document.getElementById('ce-strike-select');
    const peSel = document.getElementById('pe-strike-select');
    
    ceSel.innerHTML = strikeOptions;
    peSel.innerHTML = strikeOptions;

    if (strikes.length > 0) {
        let defaultStrike = strikes[0];
        if (selected && selected.atmStrike != null) {
            const match = strikes.find(s => Number(s) === Number(selected.atmStrike));
            if (match !== undefined) defaultStrike = match;
        }
        ceSel.value = defaultStrike; peSel.value = defaultStrike;
        API_PARAMS.ceStrike = String(defaultStrike); API_PARAMS.peStrike = String(defaultStrike);
    }
}

async function loadInitialData() {
    showLoader(); 
    
    if (!isInitialLoad) {
        try { savedVisibleRange = chart.timeScale().getVisibleRange(); } catch (e) {}
    }

    apiData = await fetchDataFromAPI();
    transformToSeries(apiData);
    applyLineVisibilityChanges();
    updateLegendLatest(); 
    
    if (apiData.length > 0) lastUpdateTimestamp = apiData[apiData.length - 1].timestamp;
    
    if (isInitialLoad) {
        chart.timeScale().fitContent();
        isInitialLoad = false;
    } else if (savedVisibleRange) {
        try { chart.timeScale().setVisibleRange(savedVisibleRange); } catch (e) {}
    }

    hideLoader(); 
}

function initCheckoutDropdown() {
    const menu = document.getElementById('checkout-menu');
    document.getElementById('checkout-btn').addEventListener('click', (e) => {
        e.stopPropagation();
        const isVisible = menu.classList.contains('show');
        closeAllDropdowns();
        if (!isVisible) {
            pendingLineVisibility = {...lineVisibility};
            syncCheckboxesFromPendingVisibility();
            menu.classList.add('show');
        }
    });

    menu.addEventListener('click', e => e.stopPropagation());
    
    document.getElementById('checkout-ok-btn').addEventListener('click', (e) => {
        e.stopPropagation();
        applyLineVisibilityChanges();
        menu.classList.remove('show');
    });

    document.getElementById('all-lines-check').addEventListener('change', e => setAllLinesChecked(e.target.checked));

    const mapping = {
        'ce-check': 'ce', 'pe-check': 'pe', 'combined-premium-check': 'combinedPremium',
        'ce-vwap-check': 'ceVwap', 'pe-vwap-check': 'peVwap', 'combined-vwap-check': 'combinedVwap',
        'combined-prev-low-check': 'combinedPrevLow'
    };

    Object.entries(mapping).forEach(([id, key]) => {
        document.getElementById(id).addEventListener('change', e => {
            pendingLineVisibility[key] = e.target.checked;
            updateAllCheckbox();
        });
    });

    document.addEventListener('click', closeAllDropdowns);
}

function syncCheckboxesFromPendingVisibility() {
    const map = {
        ce: 'ce-check', pe: 'pe-check', combinedPremium: 'combined-premium-check',
        ceVwap: 'ce-vwap-check', peVwap: 'pe-vwap-check', combinedVwap: 'combined-vwap-check',
        combinedPrevLow: 'combined-prev-low-check'
    };
    Object.entries(map).forEach(([key, id]) => {
        const el = document.getElementById(id);
        if (el) el.checked = !!pendingLineVisibility[key];
    });
    updateAllCheckbox();
}

function closeAllDropdowns() {
    document.querySelectorAll('.checkout-menu').forEach(m => m.classList.remove('show'));
}

function initDropdowns() {
    const reload = () => { isInitialLoad = true; lastUpdateTimestamp = null; loadInitialData(); };

    document.getElementById('name-select').addEventListener('change', e => { API_PARAMS.name = e.target.value; populateExpirySelect(); reload(); });
    document.getElementById('expiry-select').addEventListener('change', e => { API_PARAMS.expiry = e.target.value; populateStrikeSelects(); reload(); });

    const ceSel = document.getElementById('ce-strike-select');
    const peSel = document.getElementById('pe-strike-select');
    const syncCheck = document.getElementById('sync-strike-check');

    ceSel.addEventListener('change', e => {
        API_PARAMS.ceStrike = e.target.value;
        if (syncCheck.checked) { peSel.value = e.target.value; API_PARAMS.peStrike = e.target.value; }
        reload();
    });

    peSel.addEventListener('change', e => {
        API_PARAMS.peStrike = e.target.value;
        if (syncCheck.checked) { ceSel.value = e.target.value; API_PARAMS.ceStrike = e.target.value; }
        reload();
    });
}

async function updateIncrementalData() {
    try {
        const newData = await fetchDataFromAPI();
        const newRecords = lastUpdateTimestamp ? newData.filter(row => row.timestamp > lastUpdateTimestamp) : newData;

        if (newRecords.length === 0) { updateLastUpdateTime(); return; }

        for (const row of newRecords) {
            const t = toUnixSeconds(row.timestamp);
            if (!isFinite(t)) continue;

            const pushUpdate = (lineKey, rowKey) => {
                if (lineVisibility[lineKey] && row[rowKey] != null) {
                    const val = Number(row[rowKey]);
                    lines[lineKey].update({ time: t, value: val });
                    currentSeries[`${lineKey}Series`].push({ time: t, value: val });
                }
            };

            pushUpdate('ce', 'ce'); pushUpdate('pe', 'pe'); pushUpdate('combinedPremium', 'combinedPremium');
            pushUpdate('ceVwap', 'ceVwap'); pushUpdate('peVwap', 'peVwap'); pushUpdate('combinedVwap', 'combinedVwap');
            pushUpdate('combinedPrevLow', 'combinedPrevLow');
        }

        lastUpdateTimestamp = newRecords[newRecords.length - 1].timestamp;
        updateLegendLatest();
    } catch (err) {}
}

function startAutoRefresh() {
    if (autoRefreshInterval || document.hidden) return; 
    autoRefreshInterval = setInterval(() => updateIncrementalData(), 30000);
}

function stopAutoRefresh() {
    if (autoRefreshInterval) { clearInterval(autoRefreshInterval); autoRefreshInterval = null; }
}

async function init() {
    initDOMCache();
    loadLineVisibility();
    pendingLineVisibility = {...lineVisibility};

    document.getElementById('theme-toggle').addEventListener('click', toggleTheme);
    document.getElementById('auto-update-toggle').addEventListener('click', () => {
        autoUpdateEnabled = !autoUpdateEnabled;
        document.getElementById('auto-update-label').textContent = autoUpdateEnabled ? 'Auto: ON' : 'Auto: OFF';
        document.getElementById('auto-update-switch').classList.toggle('active', autoUpdateEnabled);
        autoUpdateEnabled ? startAutoRefresh() : stopAutoRefresh();
    });

    document.getElementById('btn-reset').addEventListener('click', () => { 
    // Instantly resets zoom/pan without reloading data
    chart.timeScale().fitContent(); 
});

    initCheckoutDropdown();
    syncCheckboxesFromVisibility();
    initDropdowns();
    
    // Explicitly set the UI to dark on load to sync everything
    document.getElementById('theme-switch').classList.add('active');
    document.querySelector('#theme-toggle .toggle-label').textContent = 'Dark';
    
    await loadGroupedData();
    await loadInitialData();
    startAutoRefresh();

    document.addEventListener('visibilitychange', () => {
        if (document.hidden) stopAutoRefresh();
        else if (autoUpdateEnabled) { updateIncrementalData(); startAutoRefresh(); }
    });

    window.addEventListener('resize', () => {
        const chartDiv = document.getElementById('chart');
        chart.applyOptions({ width: chartDiv.clientWidth, height: chartDiv.clientHeight });
    });
}

// Throttle Crosshair Updates to prevent lag
chart.subscribeCrosshairMove(param => {
    if (!crosshairUpdatePending) {
        crosshairUpdatePending = true;
        requestAnimationFrame(() => {
            updateLegendAtCrosshair(param);
            crosshairUpdatePending = false;
        });
    }
});

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();
window.addEventListener('beforeunload', stopAutoRefresh);