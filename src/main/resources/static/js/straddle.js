const API_URL = '/api/straddle/combined-chart';
const GROUPED_API_URL = '/api/straddle/grouped';
const API_PARAMS = { name: 'NIFTY', expiry: '16DEC25', ceStrike: '26000', peStrike: '26000' };
const STORAGE_KEY_LINES = 'nifty_lines_visibility_v1';
const STORAGE_KEY_FULLSCREEN = 'nifty_fullscreen_state';

let groupedData = [];
let apiData = [];
let currentSeries = {
    spotSeries: [], ceSeries: [], peSeries: [], combinedPremiumSeries: [],
    extrinsicSeries: [], avgPriceSeries: [],
    ceExtrinsicSeries: [], peExtrinsicSeries: [],
    ceVwapSeries: [], peVwapSeries: [], combinedVwapSeries: [],
    ceIvSeries: [], peIvSeries: [], combinedIvSeries: [],
    cePrevCloseSeries: [], pePrevCloseSeries: [], combinedPrevCloseSeries: [],
    cePrevLowSeries: [], pePrevLowSeries: [], combinedPrevLowSeries: [],
    ceOpen: null, peOpen: null, combinedOpen: null
};

let allCrossings = [];
let crossoverEnabled = false;
let isDarkTheme = false;
let autoRefreshInterval = null;
let autoUpdateEnabled = true;
let isFullscreen = false;
let isInitialLoad = true;
let savedVisibleRange = null;
let lastUpdateTimestamp = null;

// Default: CE, PE, Combined, opens ON; others OFF
let lineVisibility = {
    spot: false,
    ce: true,
    pe: true,
    combinedPremium: true,
    avgPrice: false,
    ceIv: false,
    peIv: false,
    combinedIv: false,
    ceExtrinsic: false,
    peExtrinsic: false,
    ceOpen: false,
    peOpen: false,
    combinedOpen: false,
    ceVwap: true,
    peVwap: true,
    combinedVwap: true,
    cePrevClose: false,
    pePrevClose: false,
    combinedPrevClose: false,
    cePrevLow: false, 
    pePrevLow: false, 
    combinedPrevLow: true
};

// Temporary state for pending selections
let pendingLineVisibility = {...lineVisibility};

function saveLineVisibility() {
    try {
        localStorage.setItem(STORAGE_KEY_LINES, JSON.stringify(lineVisibility));
    } catch (e) {
        console.warn('Failed to save line visibility', e);
    }
}

function loadLineVisibility() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY_LINES);
        if (!raw) return;
        const parsed = JSON.parse(raw);
        Object.keys(lineVisibility).forEach(k => {
            if (Object.prototype.hasOwnProperty.call(parsed, k)) {
                lineVisibility[k] = !!parsed[k];
            }
        });
    } catch (e) {
        console.warn('Failed to load line visibility', e);
    }
}

function saveFullscreenState() {
    try {
        localStorage.setItem(STORAGE_KEY_FULLSCREEN, JSON.stringify(isFullscreen));
    } catch (e) {
        console.warn('Failed to save fullscreen state', e);
    }
}

function loadFullscreenState() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY_FULLSCREEN);
        if (raw !== null) {
            isFullscreen = JSON.parse(raw);
        }
    } catch (e) {
        console.warn('Failed to load fullscreen state', e);
    }
}

function applyFullscreenState() {
    const card = document.getElementById('chart-card');
    if (isFullscreen) {
        card.classList.add('chart-fullscreen');
    } else {
        card.classList.remove('chart-fullscreen');
    }
    
    // Resize chart to fit container after applying state
    setTimeout(() => {
        const chartDiv = document.getElementById('chart');
        chart.applyOptions({
            width: chartDiv.clientWidth,
            height: chartDiv.clientHeight
        });
        // Don't call fitContent here - it will override saved viewport position
    }, 50);
}

function syncCheckboxesFromVisibility() {
    const map = {
        spot: 'spot-check',
        ce: 'ce-check',
        pe: 'pe-check',
        combinedPremium: 'combined-premium-check',
        avgPrice: 'avg-check',
        ceIv: 'ce-iv-check',
        peIv: 'pe-iv-check',
        combinedIv: 'combined-iv-check',
        ceExtrinsic: 'ce-extrinsic-check',
        peExtrinsic: 'pe-extrinsic-check',
        ceOpen: 'ce-open-check',
        peOpen: 'pe-open-check',
        combinedOpen: 'combined-open-check',
        ceVwap: 'ce-vwap-check',
        peVwap: 'pe-vwap-check',
        combinedVwap: 'combined-vwap-check',
        cePrevClose: 'ce-prev-close-check',
        pePrevClose: 'pe-prev-close-check',
        combinedPrevClose: 'combined-prev-close-check'
    };
    Object.entries(map).forEach(([key, id]) => {
        const el = document.getElementById(id);
        if (el) el.checked = !!lineVisibility[key];
    });
    updateAllCheckbox();
}

function updateAllCheckbox() {
    const vals = Object.values(pendingLineVisibility);
    const allOn = vals.every(Boolean);
    const allOff = vals.every(v => !v);
    const allBox = document.getElementById('all-lines-check');
    if (!allBox) return;
    allBox.indeterminate = !allOn && !allOff;
    allBox.checked = allOn;
}

function setAllLinesChecked(checked) {
    Object.keys(pendingLineVisibility).forEach(k => {
        pendingLineVisibility[k] = checked;
    });

    document.getElementById('spot-check').checked = checked;
    document.getElementById('ce-check').checked = checked;
    document.getElementById('pe-check').checked = checked;
    document.getElementById('combined-premium-check').checked = checked;
    document.getElementById('avg-check').checked = checked;
    document.getElementById('ce-iv-check').checked = checked;
    document.getElementById('pe-iv-check').checked = checked;
    document.getElementById('combined-iv-check').checked = checked;
    document.getElementById('ce-extrinsic-check').checked = checked;
    document.getElementById('pe-extrinsic-check').checked = checked;
    document.getElementById('ce-open-check').checked = checked;
    document.getElementById('pe-open-check').checked = checked;
    document.getElementById('combined-open-check').checked = checked;
    document.getElementById('ce-vwap-check').checked = checked;
    document.getElementById('pe-vwap-check').checked = checked;
    document.getElementById('combined-vwap-check').checked = checked;
    document.getElementById('ce-prev-close-check').checked = checked;
    document.getElementById('pe-prev-close-check').checked = checked;
    document.getElementById('combined-prev-close-check').checked = checked;

    updateAllCheckbox();
}

function applyLineVisibilityChanges() {
    // Copy pending state to actual state
    lineVisibility = {...pendingLineVisibility};

    lines.spot.setData(lineVisibility.spot ? currentSeries.spotSeries : []);
    lines.ce.setData(lineVisibility.ce ? currentSeries.ceSeries : []);
    lines.pe.setData(lineVisibility.pe ? currentSeries.peSeries : []);
    lines.combinedPremium.setData(lineVisibility.combinedPremium ? currentSeries.combinedPremiumSeries : []);
    lines.avgPrice.setData(lineVisibility.avgPrice ? currentSeries.avgPriceSeries : []);
    lines.ceIv.setData(lineVisibility.ceIv ? currentSeries.ceIvSeries : []);
    lines.peIv.setData(lineVisibility.peIv ? currentSeries.peIvSeries : []);
    lines.combinedIv.setData(lineVisibility.combinedIv ? currentSeries.combinedIvSeries : []);
    lines.ceExtrinsic.setData(lineVisibility.ceExtrinsic ? currentSeries.ceExtrinsicSeries : []);
    lines.peExtrinsic.setData(lineVisibility.peExtrinsic ? currentSeries.peExtrinsicSeries : []);
    lines.ceVwap.setData(lineVisibility.ceVwap ? currentSeries.ceVwapSeries : []);
    lines.peVwap.setData(lineVisibility.peVwap ? currentSeries.peVwapSeries : []);
    lines.combinedVwap.setData(lineVisibility.combinedVwap ? currentSeries.combinedVwapSeries : []);
    lines.cePrevClose.setData(lineVisibility.cePrevClose ? currentSeries.cePrevCloseSeries : []);
    lines.pePrevClose.setData(lineVisibility.pePrevClose ? currentSeries.pePrevCloseSeries : []);
    lines.combinedPrevClose.setData(lineVisibility.combinedPrevClose ? currentSeries.combinedPrevCloseSeries : []);
    lines.cePrevLow.setData(lineVisibility.cePrevLow ? currentSeries.cePrevLowSeries : []);
    lines.pePrevLow.setData(lineVisibility.pePrevLow ? currentSeries.pePrevLowSeries : []);
    lines.combinedPrevLow.setData(lineVisibility.combinedPrevLow ? currentSeries.combinedPrevLowSeries : []);
    drawOpenPriceLines();

    updateLegendLatest();
    saveLineVisibility();
}

function toUnixSeconds(isoString) {
    // API sends timestamps like "2025-12-23T15:30:12+05:30" (IST)
    // We want to display 15:30 on the chart
    // Parse the timestamp and extract IST time components
    const date = new Date(isoString);
    
    // Get IST time components
    const istString = date.toLocaleString('en-US', {
        timeZone: 'Asia/Kolkata',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
    });
    
    // Parse the IST string to get a date object (treated as UTC for chart purposes)
    const [datePart, timePart] = istString.split(', ');
    const [month, day, year] = datePart.split('/');
    const [hour, minute, second] = timePart.split(':');
    
    // Create a UTC date with IST time values (this tricks the chart into showing IST times)
    const utcDate = Date.UTC(year, month - 1, day, hour, minute, second);
    
    return Math.floor(utcDate / 1000);
}

function showError(message) {
    document.getElementById('crossings-container').innerHTML = `<div class="no-crossings">${message}</div>`;
    document.getElementById('status-indicator').className = 'status-indicator error';
}

function updateLastUpdateTime() {
    const now = new Date();
    document.getElementById('last-update-time').textContent = now.toLocaleTimeString('en-IN', {
        timeZone: 'Asia/Kolkata', hour12: false
    });
}

function transformToSeries(apiArray) {
    const series = {
        spot: [],
        ce: [],
        pe: [],
        combinedPremium: [],
        extrinsic: [],
        avgPrice: [],
        ceIv: [],
        peIv: [],
        combinedIv: [],
        ceExtrinsic: [],
        peExtrinsic: [],
        ceVwap: [],
        peVwap: [],
        combinedVwap: [],
        cePrevClose: [],
        pePrevClose: [],
        combinedPrevClose: [],
        cePrevLow: [], 
        pePrevLow: [], 
        combinedPrevLow: []
    };

    for (const row of apiArray) {
        const t = toUnixSeconds(row.timestamp);
        if (!isFinite(t) || row.spot == null) continue;

        const vals = {
            spot: Number(row.spot),
            ce: Number(row.ce),
            pe: Number(row.pe),
            combinedPremium: row.combinedPremium != null ? Number(row.combinedPremium) : null,
            extrinsic: row.extrinsic != null ? Number(row.extrinsic) : null,
            avgPrice: row.avgPrice != null ? Number(row.avgPrice) : null,
            ceIv: row.ceIV != null ? Number(row.ceIV) : null,
            peIv: row.peIV != null ? Number(row.peIV) : null,
            combinedIv: row.combinedIV != null ? Number(row.combinedIV) : null,
            ceExtrinsic: row.ceExtrinsic != null ? Number(row.ceExtrinsic) : null,
            peExtrinsic: row.peExtrinsic != null ? Number(row.peExtrinsic) : null,
            ceVwap: row.ceVwap != null ? Number(row.ceVwap) : null,
            peVwap: row.peVwap != null ? Number(row.peVwap) : null,
            combinedVwap: row.combinedVwap != null ? Number(row.combinedVwap) : null,
            cePrevClose: row.cePrevClose != null ? Number(row.cePrevClose) : null,
            pePrevClose: row.pePrevClose != null ? Number(row.pePrevClose) : null,
            combinedPrevClose: row.combinedPrevClose != null ? Number(row.combinedPrevClose) : null
        };

        if (!Number.isFinite(vals.spot)) continue;

        series.spot.push({ time: t, value: vals.spot });
        if (Number.isFinite(vals.ce)) series.ce.push({ time: t, value: vals.ce });
        if (Number.isFinite(vals.pe)) series.pe.push({ time: t, value: vals.pe });
        if (vals.combinedPremium != null && Number.isFinite(vals.combinedPremium))
            series.combinedPremium.push({ time: t, value: vals.combinedPremium });
        if (vals.extrinsic != null && Number.isFinite(vals.extrinsic))
            series.extrinsic.push({ time: t, value: vals.extrinsic });
        if (vals.avgPrice != null && Number.isFinite(vals.avgPrice))
            series.avgPrice.push({ time: t, value: vals.avgPrice });
        if (vals.ceIv != null && Number.isFinite(vals.ceIv))
            series.ceIv.push({ time: t, value: vals.ceIv });
        if (vals.peIv != null && Number.isFinite(vals.peIv))
            series.peIv.push({ time: t, value: vals.peIv });
        if (vals.combinedIv != null && Number.isFinite(vals.combinedIv))
            series.combinedIv.push({ time: t, value: vals.combinedIv });
        if (vals.ceExtrinsic != null && Number.isFinite(vals.ceExtrinsic))
            series.ceExtrinsic.push({ time: t, value: vals.ceExtrinsic });
        if (vals.peExtrinsic != null && Number.isFinite(vals.peExtrinsic))
            series.peExtrinsic.push({ time: t, value: vals.peExtrinsic });
        if (vals.ceVwap != null && Number.isFinite(vals.ceVwap))
            series.ceVwap.push({ time: t, value: vals.ceVwap });
        if (vals.peVwap != null && Number.isFinite(vals.peVwap))
            series.peVwap.push({ time: t, value: vals.peVwap });
        if (vals.combinedVwap != null && Number.isFinite(vals.combinedVwap))
            series.combinedVwap.push({ time: t, value: vals.combinedVwap });
        if (vals.cePrevClose != null && Number.isFinite(vals.cePrevClose))
            series.cePrevClose.push({ time: t, value: vals.cePrevClose });
        if (vals.pePrevClose != null && Number.isFinite(vals.pePrevClose))
            series.pePrevClose.push({ time: t, value: vals.pePrevClose });
        if (vals.combinedPrevClose != null && Number.isFinite(vals.combinedPrevClose))
            series.combinedPrevClose.push({ time: t, value: vals.combinedPrevClose });
        if (row.cePrevLow != null) series.cePrevLow.push({ time: t, value: Number(row.cePrevLow) });
        if (row.pePrevLow != null) series.pePrevLow.push({ time: t, value: Number(row.pePrevLow) });
        if (row.combinedPrevLow != null) series.combinedPrevLow.push({ time: t, value: Number(row.combinedPrevLow) });
    }

    if (apiArray.length > 0) {
        const first = apiArray[0];
        currentSeries.ceOpen = Number.isFinite(Number(first.ceOpen)) ? Number(first.ceOpen) : null;
        currentSeries.peOpen = Number.isFinite(Number(first.peOpen)) ? Number(first.peOpen) : null;
        currentSeries.combinedOpen = Number.isFinite(Number(first.combinedOpen)) ? Number(first.combinedOpen) : null;
        currentSeries.cePrevLowSeries = series.cePrevLow;
        currentSeries.pePrevLowSeries = series.pePrevLow;
        currentSeries.combinedPrevLowSeries = series.combinedPrevLow;
    }

    Object.assign(currentSeries, {
        spotSeries: series.spot,
        ceSeries: series.ce,
        peSeries: series.pe,
        combinedPremiumSeries: series.combinedPremium,
        extrinsicSeries: series.extrinsic,
        avgPriceSeries: series.avgPrice,
        ceIvSeries: series.ceIv,
        peIvSeries: series.peIv,
        combinedIvSeries: series.combinedIv,
        ceExtrinsicSeries: series.ceExtrinsic,
        peExtrinsicSeries: series.peExtrinsic,
        ceVwapSeries: series.ceVwap,
        peVwapSeries: series.peVwap,
        combinedVwapSeries: series.combinedVwap,
        cePrevCloseSeries: series.cePrevClose,
        pePrevCloseSeries: series.pePrevClose,
        combinedPrevCloseSeries: series.combinedPrevClose
    });

    return series;
}

const chart = LightweightCharts.createChart(document.getElementById('chart'), {
    layout: { backgroundColor: '#071421', textColor: '#dbeafe' },
    rightPriceScale: { borderVisible: false, visible: true },
    leftPriceScale: { borderVisible: false, visible: true },
    timeScale: {
        borderVisible: false, 
        timeVisible: true, 
        secondsVisible: true,
        tickMarkFormatter: (time) => {
            // Time values are already IST (stored as UTC for display purposes)
            const date = new Date(time * 1000);
            const hours = String(date.getUTCHours()).padStart(2, '0');
            const minutes = String(date.getUTCMinutes()).padStart(2, '0');
            const seconds = String(date.getUTCSeconds()).padStart(2, '0');
            return `${hours}:${minutes}:${seconds}`;
        }
    },
    grid: { vertLines: { visible: false }, horzLines: { color: 'rgba(255,255,255,0.03)' } },
    crosshair: { mode: LightweightCharts.CrosshairMode.Normal }
});

const lines = {
    spot: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 3,
        color: '#000000',
        priceScaleId: 'left'
    }),
    ce: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 3,
        color: '#22c55e',
        priceScaleId: 'right'
    }),
    pe: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 3,
        color: '#ef4444',
        priceScaleId: 'right'
    }),
    combinedPremium: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2.5,
        color: '#3b82f6',
        priceScaleId: 'right',
        title: 'Comb Price'
    }),
    avgPrice: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2,
        color: '#301934',
        priceScaleId: 'right'
    }),
    ceIv: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2.5,
        color: '#ec4899',
        priceScaleId: 'right'
    }),
    peIv: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2.5,
        color: '#6366f1',
        priceScaleId: 'right'
    }),
    combinedIv: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2.5,
        color: '#14b8a6',
        priceScaleId: 'right'
    }),
    extrinsic: chart.addLineSeries({     // kept internally but not surfaced
        priceLineVisible: false,
        lastValueVisible: false,
        lineWidth: 2,
        color: '#eab308',
        priceScaleId: 'right'
    }),
    ceExtrinsic: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2,
        color: '#16a34a',
        priceScaleId: 'right'
    }),
    peExtrinsic: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2,
        color: '#b91c1c',
        priceScaleId: 'right'
    }),
    ceOpen: chart.addLineSeries({
        priceLineVisible: true,
        lastValueVisible: true,
        lineWidth: 4,
        color: '#86efac',
        lineStyle: 1,
        priceScaleId: 'right'
    }),
    peOpen: chart.addLineSeries({
        priceLineVisible: true,
        lastValueVisible: true,
        lineWidth: 4,
        color: '#fecaca',
        lineStyle: 1,
        priceScaleId: 'right'
    }),
    combinedOpen: chart.addLineSeries({
        priceLineVisible: true,
        lastValueVisible: true,
        lineWidth: 3,
        color: '#d6a65a',
        lineStyle: 2,
        priceScaleId: 'right'
    }),
    ceVwap: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2.5,
        color: '#a78bfa',
        priceScaleId: 'right'
    }),
    peVwap: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2.5,
        color: '#fb923c',
        priceScaleId: 'right'
    }),
    combinedVwap: chart.addLineSeries({
        priceLineVisible: false,
        lastValueVisible: true,
        lineWidth: 2.5,
        color: '#06b6d4',
        priceScaleId: 'right',
        title: 'Comb Vwap'
    }),
    cePrevClose: chart.addLineSeries({
        priceLineVisible: true,
        lastValueVisible: true,
        lineWidth: 3,
        color: '#10b981',
        lineStyle: 1,
        priceScaleId: 'right'
    }),
    pePrevClose: chart.addLineSeries({
        priceLineVisible: true,
        lastValueVisible: true,
        lineWidth: 3,
        color: '#f59e0b',
        lineStyle: 1,
        priceScaleId: 'right'
    }),
    combinedPrevClose: chart.addLineSeries({
        priceLineVisible: true,
        lastValueVisible: true,
        lineWidth: 3,
        color: '#8b5cf6',
        lineStyle: 2,
        priceScaleId: 'right'
    }),
    cePrevLow: chart.addLineSeries({ 
        lineWidth: 2, color: '#14532d', lineStyle: 2, priceScaleId: 'right', title: 'Prev CE Low' 
    }),
    pePrevLow: chart.addLineSeries({ 
        lineWidth: 2, color: '#7f1d1d', lineStyle: 2, priceScaleId: 'right', title: 'Prev PE Low' 
    }),
    combinedPrevLow: chart.addLineSeries({ 
        lineWidth: 2, color: '#581c87', lineStyle: 1, priceScaleId: 'right', title: 'Comb Prev Low' 
    })
};

function updateChartTheme() {
    const bgColor = isDarkTheme ? '#071421' : '#ffffff';
    const textColor = isDarkTheme ? '#dbeafe' : '#1e293b';
    const gridColor = isDarkTheme ? 'rgba(255,255,255,0.03)' : '#f1f5f9';

    chart.applyOptions({
        layout: { backgroundColor: bgColor, textColor: textColor },
        grid: { vertLines: { visible: false }, horzLines: { color: gridColor } }
    });
}

function drawOpenPriceLines() {
    const timestamps = currentSeries.spotSeries.map(p => p.time);
    if (!timestamps.length) {
        lines.ceOpen.setData([]);
        lines.peOpen.setData([]);
        lines.combinedOpen.setData([]);
        return;
    }

    const opens = {
        ceOpen: Number(currentSeries.ceOpen),
        peOpen: Number(currentSeries.peOpen),
        combinedOpen: Number(currentSeries.combinedOpen)
    };

    Object.keys(opens).forEach(key => {
        if (lineVisibility[key] && Number.isFinite(opens[key])) {
            lines[key].setData(timestamps.map(t => ({ time: t, value: opens[key] })));
        } else {
            lines[key].setData([]);
        }
    });
}

function loadInitialData() {
    document.querySelectorAll('.legend .item').forEach(item => {
        const lineKey = item.dataset.line;
        if (lineKey && !lineVisibility[lineKey]) {
            item.classList.add('hidden');
        } else {
            item.classList.remove('hidden');
        }
    });
}

function updateLegendLatest() {
    const fmt = (v) => (v !== null && isFinite(v)) ? Number(v).toFixed(2) : '-';
    
    const seriesKeys = [
        'spot', 'ce', 'pe', 'combinedPremium', 'avgPrice', 
        'ceIv', 'peIv', 'combinedIv', 'ceExtrinsic', 'peExtrinsic', 
        'ceVwap', 'peVwap', 'combinedVwap', 'cePrevClose', 'pePrevClose', 
        'combinedPrevClose', 'cePrevLow', 'pePrevLow', 'combinedPrevLow'
    ];

    let latestPremium = null;
    let latestVwap = null;

    seriesKeys.forEach(key => {
        const data = currentSeries[key + 'Series'];
        const val = (data && data.length > 0) ? data[data.length - 1].value : null;
        
        // Match these keys exactly to the seriesKeys above
        if (key === 'combinedPremium') latestPremium = val;
        if (key === 'combinedVwap') latestVwap = val;

        const id = 'val-' + key.replace(/([A-Z])/g, "-$1").toLowerCase();
        const el = document.getElementById(id);
        if (el) el.textContent = fmt(val);
    });

    calculateDiff(latestPremium, latestVwap);
    updateLegendVisibility();
}

function updateLegendVisibility() {
    // Select all legend items that have a data-line attribute
    document.querySelectorAll('.legend .item').forEach(item => {
        const lineKey = item.dataset.line;
        
        // If the line is turned off in our visibility object, add the 'hidden' class
        if (lineKey && !lineVisibility[lineKey]) {
            item.classList.add('hidden');
        } else {
            item.classList.remove('hidden');
        }
    });
}

function updateLegendAtCrosshair(param) {
    if (!param || !param.time || !param.point || param.point.x < 0 || param.point.y < 0) {
        updateLegendLatest();
        return;
    }

    const getData = (line) => param.seriesData.get(line);
    const getValue = (data) => data && data.value !== undefined ? data.value : null;

    // 1. Capture the values for the meter
    const prem = getValue(getData(lines.combinedPremium));
    const vwap = getValue(getData(lines.combinedVwap));

    // 2. Capture all values for the legend
    const values = {
        spot: getValue(getData(lines.spot)),
        ce: getValue(getData(lines.ce)),
        pe: getValue(getData(lines.pe)),
        combinedPremium: prem,
        avgPrice: getValue(getData(lines.avgPrice)),
        ceIv: getValue(getData(lines.ceIv)),
        peIv: getValue(getData(lines.peIv)),
        combinedIv: getValue(getData(lines.combinedIv)),
        ceExtrinsic: getValue(getData(lines.ceExtrinsic)),
        peExtrinsic: getValue(getData(lines.peExtrinsic)),
        ceVwap: getValue(getData(lines.ceVwap)),
        peVwap: getValue(getData(lines.peVwap)),
        combinedVwap: vwap,
        cePrevClose: getValue(getData(lines.cePrevClose)),
        pePrevClose: getValue(getData(lines.pePrevClose)),
        combinedPrevClose: getValue(getData(lines.combinedPrevClose)),
        ceOpen: getValue(getData(lines.ceOpen)) ?? currentSeries.ceOpen,
        peOpen: getValue(getData(lines.peOpen)) ?? currentSeries.peOpen,
        combinedOpen: getValue(getData(lines.combinedOpen)) ?? currentSeries.combinedOpen,
        cePrevLow: getValue(getData(lines.cePrevLow)),
        pePrevLow: getValue(getData(lines.pePrevLow)),
        combinedPrevLow: getValue(getData(lines.combinedPrevLow))
    };

    const fmt = (v) => v !== null && Number.isFinite(v) ? v.toFixed(2) : '-';

    // Update standard legend labels
    document.getElementById('val-spot').textContent = fmt(values.spot);
    document.getElementById('val-ce').textContent = fmt(values.ce);
    document.getElementById('val-pe').textContent = fmt(values.pe);
    document.getElementById('val-combined-premium').textContent = fmt(values.combinedPremium);
    document.getElementById('val-avg').textContent = fmt(values.avgPrice);
    document.getElementById('val-ce-iv').textContent = fmt(values.ceIv);
    document.getElementById('val-pe-iv').textContent = fmt(values.peIv);
    document.getElementById('val-combined-iv').textContent = fmt(values.combinedIv);
    document.getElementById('val-ce-extrinsic').textContent = fmt(values.ceExtrinsic);
    document.getElementById('val-pe-extrinsic').textContent = fmt(values.peExtrinsic);
    document.getElementById('val-ce-vwap').textContent = fmt(values.ceVwap);
    document.getElementById('val-pe-vwap').textContent = fmt(values.peVwap);
    document.getElementById('val-combined-vwap').textContent = fmt(values.combinedVwap);
    document.getElementById('val-ce-prev-close').textContent = fmt(values.cePrevClose);
    document.getElementById('val-pe-prev-close').textContent = fmt(values.pePrevClose);
    document.getElementById('val-combined-prev-close').textContent = fmt(values.combinedPrevClose);
    document.getElementById('val-ce-open').textContent = fmt(values.ceOpen);
    document.getElementById('val-pe-open').textContent = fmt(values.peOpen);
    document.getElementById('val-combined-open').textContent = fmt(values.combinedOpen);
    document.getElementById('val-ce-prev-low').textContent = fmt(values.cePrevLow);
    document.getElementById('val-pe-prev-low').textContent = fmt(values.pePrevLow);
    document.getElementById('val-combined-prev-low').textContent = fmt(values.combinedPrevLow);

    // 3. Trigger the meter update with the specific values
    calculateDiff(prem, vwap);
}

function calculateDiff(prem, vwap) {
    const meter = document.getElementById('diff-meter');
    if (!meter) return;

    // Added explicit check for Number type to ensure it doesn't fail on 0
    const isValid = (val) => val !== null && typeof val === 'number';

    if (isValid(prem) && isValid(vwap) && lineVisibility.combinedPremium && lineVisibility.combinedVwap) {
        const diff = prem - vwap;
        const pct = (diff / vwap) * 100;
        const sign = diff >= 0 ? '+' : '';
        
        meter.style.display = 'inline-flex';
        meter.textContent = `Diff: ${sign}${diff.toFixed(2)} (${sign}${pct.toFixed(2)}%)`;
        
        if (diff >= 0) {
            meter.className = 'diff-meter diff-positive';
        } else {
            meter.className = 'diff-meter diff-negative';
        }
    } else {
        meter.style.display = 'none';
    }
}

function detectCrossovers(s1, s2, n1, n2) {
    const crossings = [];
    if (s1.length < 2 || s2.length < 2) return crossings;

    for (let i = 1; i < Math.min(s1.length, s2.length); i++) {
        const [prev1, curr1, prev2, curr2] = [s1[i-1].value, s1[i].value, s2[i-1].value, s2[i].value];
        if (![prev1, curr1, prev2, curr2].every(v => isFinite(v))) continue;

        const crossedUp = prev1 < prev2 && curr1 > curr2;
        const crossedDown = prev1 > prev2 && curr1 < curr2;

        if (crossedUp || crossedDown) {
            const [t1, t2] = [s1[i-1].time, s1[i].time];
            const ratio = Math.abs(prev2 - prev1) / (Math.abs(curr1 - prev2) + Math.abs(prev2 - prev1) || 1);
            const time = t1 + (t2 - t1) * ratio;
            const price = prev1 + (curr1 - prev1) * ratio;

            crossings.push({
                time, price, gap: 0,
                type: crossedUp ? `${n1} crosses above ${n2}` : `${n1} crosses below ${n2}`,
                pair: `${n1}-${n2}`,
                direction: crossedUp ? 'up' : 'down',
                ceValue: n1 === 'CE' ? curr1 : curr2,
                peValue: n1 === 'PE' ? curr1 : curr2
            });
        }
    }
    return crossings;
}

function addCrossingMarkers() {
    if (!lineVisibility.ce) return;
    const markers = allCrossings.map(c => ({
        time: c.time, position: 'inBar', color: '#f472b6', shape: 'circle'
    }));
    lines.ce.setMarkers(markers);
}

function clearCrossingMarkers() { lines.ce.setMarkers([]); }

function updateCrossingsDisplay() {
    const container = document.getElementById('crossings-container');
    if (allCrossings.length === 0) {
        container.innerHTML = '<div class="no-crossings">No crossovers detected yet.</div>';
        return;
    }

    container.innerHTML = allCrossings.map((c, i) => {
        // Time values are already IST (stored as UTC for display purposes)
        const date = new Date(c.time * 1000);
        const hours = String(date.getUTCHours()).padStart(2, '0');
        const minutes = String(date.getUTCMinutes()).padStart(2, '0');
        const seconds = String(date.getUTCSeconds()).padStart(2, '0');
        const timeStr = `${hours}:${minutes}:${seconds}`;

        return `
      <div class="crossing-item" data-crossing-index="${i}">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div style="font-weight:600;font-size:12px">CE-PE Cross</div>
          <div style="color:#94a3b8;font-size:11px">${timeStr}</div>
        </div>
        <div style="font-weight:600;margin-top:6px">CE: ₹${c.ceValue.toFixed(2)} | PE: ₹${c.peValue.toFixed(2)}</div>
        <div style="color:#94a3b8;font-size:12px;margin-top:6px">${c.type}</div>
      </div>
    `;
    }).join('');

    container.querySelectorAll('.crossing-item').forEach((item, i) => {
        item.addEventListener('click', () => {
            container.querySelectorAll('.crossing-item').forEach(el => el.classList.remove('highlighted'));
            item.classList.add('highlighted');
            highlightCrossingOnChart(allCrossings[i]);
        });
    });
}

function highlightCrossingOnChart(crossing) {
    const markers = allCrossings.map(c => {
        const isSelected = c.time === crossing.time && c.type === crossing.type;
        return {
            time: c.time,
            position: 'inBar',
            color: isSelected ? '#22c55e' : '#f472b6',
            shape: 'circle',
            size: isSelected ? 2 : 1,
            text: isSelected ? `CE: ₹${c.ceValue.toFixed(2)} = PE: ₹${c.peValue.toFixed(2)}` : undefined
        };
    });

    lines.ce.setMarkers(markers);

    const visibleRange = chart.timeScale().getVisibleRange();
    if (!visibleRange || crossing.time < visibleRange.from || crossing.time > visibleRange.to) {
        chart.timeScale().setVisibleRange({ from: crossing.time - 300, to: crossing.time + 300 });
    }
}

async function fetchDataFromAPI() {
    try {
        const params = new URLSearchParams(API_PARAMS);
        const url = `${API_URL}?${params}`;
        console.log('Fetching data from:', url); // Debug log
        const response = await fetch(url);
        if (!response.ok) throw new Error(`API failed: ${response.status}`);
        const result = await response.json();
        document.getElementById('status-indicator').className = 'status-indicator live';
        updateLastUpdateTime();
        return result.data || [];
    } catch (err) {
        console.error('Error fetching data:', err);
        showError('Error fetching data. Please try again.');
        return [];
    }
}

async function loadGroupedData() {
    try {
        const res = await fetch(GROUPED_API_URL);
        if (!res.ok) throw new Error('Grouped API failed: ' + res.status);
        groupedData = await res.json();
        populateNameSelect();
    } catch (err) {
        console.error('Error loading grouped data', err);
    }
}

function populateNameSelect() {
    const sel = document.getElementById('name-select');
    sel.innerHTML = groupedData.map(item =>
        `<option value="${item.name}">${item.name}</option>`
    ).join('');
    API_PARAMS.name = sel.value;
    populateExpirySelect();
}

function populateExpirySelect() {
    const nameSel = document.getElementById('name-select');
    const expirySel = document.getElementById('expiry-select');
    const selected = groupedData.find(x => x.name === nameSel.value);
    const expiries = selected ? Object.keys(selected.expiries) : [];

    expirySel.innerHTML = expiries.map(exp =>
        `<option value="${exp}">${exp}</option>`
    ).join('');
    API_PARAMS.expiry = expirySel.value;
    populateStrikeSelects();
}

function populateStrikeSelects() {
    const nameSel = document.getElementById('name-select');
    const expirySel = document.getElementById('expiry-select');
    const ceSel = document.getElementById('ce-strike-select');
    const peSel = document.getElementById('pe-strike-select');

    const selected = groupedData.find(x => x.name === nameSel.value);
    const strikes = selected ? (selected.expiries[expirySel.value] || []) : [];

    const strikeOptions = strikes.map(s => `<option value="${s}">${s}</option>`).join('');
    ceSel.innerHTML = strikeOptions;
    peSel.innerHTML = strikeOptions;

    if (strikes.length > 0) {
        let defaultStrike = strikes[0];
        if (selected && selected.atmStrike != null) {
            const atm = Number(selected.atmStrike);
            const match = strikes.find(s => Number(s) === atm);
            if (match !== undefined) defaultStrike = match;
        }

        ceSel.value = defaultStrike;
        peSel.value = defaultStrike;
        API_PARAMS.ceStrike = String(defaultStrike);
        API_PARAMS.peStrike = String(defaultStrike);
    }
}

async function loadInitialData() {
    // Save current visible range before reload (if not initial load)
    if (!isInitialLoad) {
        try {
            savedVisibleRange = chart.timeScale().getVisibleRange();
        } catch (e) {
            console.log('Could not save visible range:', e);
        }
    }

    apiData = await fetchDataFromAPI();
    if (apiData.length === 0) {
        showError('No data available for the selected filters.');
        return;
    }

    transformToSeries(apiData);

    lines.spot.setData(lineVisibility.spot ? currentSeries.spotSeries : []);
    lines.ce.setData(lineVisibility.ce ? currentSeries.ceSeries : []);
    lines.pe.setData(lineVisibility.pe ? currentSeries.peSeries : []);
    lines.combinedPremium.setData(lineVisibility.combinedPremium ? currentSeries.combinedPremiumSeries : []);
    lines.avgPrice.setData(lineVisibility.avgPrice ? currentSeries.avgPriceSeries : []);
    lines.ceIv.setData(lineVisibility.ceIv ? currentSeries.ceIvSeries : []);
    lines.peIv.setData(lineVisibility.peIv ? currentSeries.peIvSeries : []);
    lines.combinedIv.setData(lineVisibility.combinedIv ? currentSeries.combinedIvSeries : []);
    lines.ceExtrinsic.setData(lineVisibility.ceExtrinsic ? currentSeries.ceExtrinsicSeries : []);
    lines.peExtrinsic.setData(lineVisibility.peExtrinsic ? currentSeries.peExtrinsicSeries : []);
    lines.ceVwap.setData(lineVisibility.ceVwap ? currentSeries.ceVwapSeries : []);
    lines.peVwap.setData(lineVisibility.peVwap ? currentSeries.peVwapSeries : []);
    lines.combinedVwap.setData(lineVisibility.combinedVwap ? currentSeries.combinedVwapSeries : []);
    lines.cePrevClose.setData(lineVisibility.cePrevClose ? currentSeries.cePrevCloseSeries : []);
    lines.pePrevClose.setData(lineVisibility.pePrevClose ? currentSeries.pePrevCloseSeries : []);
    lines.combinedPrevClose.setData(lineVisibility.combinedPrevClose ? currentSeries.combinedPrevCloseSeries : []);
    lines.cePrevLow.setData(lineVisibility.cePrevLow ? currentSeries.cePrevLowSeries : []);
    lines.pePrevLow.setData(lineVisibility.pePrevLow ? currentSeries.pePrevLowSeries : []);
    lines.combinedPrevLow.setData(lineVisibility.combinedPrevLow ? currentSeries.combinedPrevLowSeries : []);
    drawOpenPriceLines();

    allCrossings = [];
    if (lineVisibility.ce && lineVisibility.pe) {
        allCrossings = detectCrossovers(currentSeries.ceSeries, currentSeries.peSeries, 'CE', 'PE');
        allCrossings.sort((a, b) => a.time - b.time);
    }

    if (crossoverEnabled) {
        addCrossingMarkers();
        updateCrossingsDisplay();
    } else {
        clearCrossingMarkers();
        document.getElementById('crossings-container').innerHTML =
            '<div class="no-crossings">Enable "Crossovers" to detect CE-PE intersections.</div>';
    }

    updateLegendLatest();
    
    // Store last timestamp for incremental updates
    if (apiData.length > 0) {
        lastUpdateTimestamp = apiData[apiData.length - 1].timestamp;
        console.log('Stored last timestamp:', lastUpdateTimestamp);
    }
    
    // Restore viewport position
    if (isInitialLoad) {
        // Only fit content on first load
        chart.timeScale().fitContent();
        isInitialLoad = false;
    } else if (savedVisibleRange) {
        // Restore saved viewport on subsequent reloads
        try {
            chart.timeScale().setVisibleRange(savedVisibleRange);
        } catch (e) {
            console.log('Could not restore visible range:', e);
        }
    }
    
    // Apply fullscreen state after data load
    applyFullscreenState();
}

function initCheckoutDropdown() {
    const btn = document.getElementById('checkout-btn');
    const menu = document.getElementById('checkout-menu');
    const okBtn = document.getElementById('checkout-ok-btn');

    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const isVisible = menu.classList.contains('show');
        closeAllDropdowns();
        if (!isVisible) {
            // Reset pending state to current state when opening
            pendingLineVisibility = {...lineVisibility};
            syncCheckboxesFromPendingVisibility();
            menu.classList.add('show');
        }
    });

    // Prevent menu from closing when clicking inside it
    menu.addEventListener('click', (e) => {
        e.stopPropagation();
    });

    // OK button applies the changes
    okBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        applyLineVisibilityChanges();
        menu.classList.remove('show');
        closeAllDropdowns();
    
        console.log("Lines applied and menu closed.");
    });

    document.getElementById('all-lines-check').addEventListener('change', (e) => {
        setAllLinesChecked(e.target.checked);
    });

    const checkboxHandlers = {
        'spot-check': 'spot',
        'ce-check': 'ce',
        'pe-check': 'pe',
        'combined-premium-check': 'combinedPremium',
        'avg-check': 'avgPrice',
        'ce-iv-check': 'ceIv',
        'pe-iv-check': 'peIv',
        'combined-iv-check': 'combinedIv',
        'ce-extrinsic-check': 'ceExtrinsic',
        'pe-extrinsic-check': 'peExtrinsic',
        'ce-open-check': 'ceOpen',
        'pe-open-check': 'peOpen',
        'combined-open-check': 'combinedOpen',
        'ce-vwap-check': 'ceVwap',
        'pe-vwap-check': 'peVwap',
        'combined-vwap-check': 'combinedVwap',
        'ce-prev-close-check': 'cePrevClose',
        'pe-prev-close-check': 'pePrevClose',
        'combined-prev-close-check': 'combinedPrevClose',
        'ce-prev-low-check': 'cePrevLow',
        'pe-prev-low-check': 'pePrevLow',
        'combined-prev-low-check': 'combinedPrevLow'
    };

    Object.entries(checkboxHandlers).forEach(([id, key]) => {
        document.getElementById(id).addEventListener('change', (e) => {
            pendingLineVisibility[key] = e.target.checked;
            updateAllCheckbox();
        });
    });

    document.getElementById('btn-max-chart').addEventListener('click', () => {
        const card = document.getElementById('chart-card');
        isFullscreen = !isFullscreen;
        
        // Save current viewport before toggling
        const currentRange = chart.timeScale().getVisibleRange();
        
        if (isFullscreen) {
            card.classList.add('chart-fullscreen');
        } else {
            card.classList.remove('chart-fullscreen');
        }

        saveFullscreenState();

        setTimeout(() => {
            const chartDiv = document.getElementById('chart');

            chart.applyOptions({
                width: chartDiv.clientWidth,
                height: chartDiv.clientHeight
            });

            // Restore viewport position after resize
            if (currentRange) {
                chart.timeScale().setVisibleRange(currentRange);
            }
        }, 200);
    });

    document.addEventListener('click', closeAllDropdowns);
}

function syncCheckboxesFromPendingVisibility() {
    const map = {
        spot: 'spot-check',
        ce: 'ce-check',
        pe: 'pe-check',
        combinedPremium: 'combined-premium-check',
        avgPrice: 'avg-check',
        ceIv: 'ce-iv-check',
        peIv: 'pe-iv-check',
        combinedIv: 'combined-iv-check',
        ceExtrinsic: 'ce-extrinsic-check',
        peExtrinsic: 'pe-extrinsic-check',
        ceOpen: 'ce-open-check',
        peOpen: 'pe-open-check',
        combinedOpen: 'combined-open-check',
        ceVwap: 'ce-vwap-check',
        peVwap: 'pe-vwap-check',
        combinedVwap: 'combined-vwap-check',
        cePrevClose: 'ce-prev-close-check',
        pePrevClose: 'pe-prev-close-check',
        combinedPrevClose: 'combined-prev-close-check',
        cePrevLow: 'ce-prev-low-check',
        pePrevLow: 'pe-prev-low-check',
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
    document.getElementById('name-select').addEventListener('change', (e) => {
        API_PARAMS.name = e.target.value;
        console.log('Name changed to:', API_PARAMS.name); // Debug log
        isInitialLoad = true; // Reset to fit content for new data
        lastUpdateTimestamp = null; // Reset timestamp for new data
        populateExpirySelect();
        loadInitialData();
    });

    document.getElementById('expiry-select').addEventListener('change', (e) => {
        API_PARAMS.expiry = e.target.value;
        console.log('Expiry changed to:', API_PARAMS.expiry); // Debug log
        isInitialLoad = true; // Reset to fit content for new data
        lastUpdateTimestamp = null; // Reset timestamp for new data
        populateStrikeSelects();
        loadInitialData();
    });

    const ceSelect = document.getElementById('ce-strike-select');
    const peSelect = document.getElementById('pe-strike-select');
    const syncCheck = document.getElementById('sync-strike-check');

    ceSelect.addEventListener('change', (e) => {
        const val = e.target.value;
        API_PARAMS.ceStrike = String(val);
        
        // If sync is checked, update PE select and params
        if (syncCheck.checked) {
            peSelect.value = val;
            API_PARAMS.peStrike = String(val);
        }

        console.log('CE Strike changed to:', API_PARAMS.ceStrike);
        isInitialLoad = true; 
        lastUpdateTimestamp = null;
        loadInitialData();
    });

    peSelect.addEventListener('change', (e) => {
        const val = e.target.value;
        API_PARAMS.peStrike = String(val);
        
        // If sync is checked, update CE select and params
        if (syncCheck.checked) {
            ceSelect.value = val;
            API_PARAMS.ceStrike = String(val);
        }

        console.log('PE Strike changed to:', API_PARAMS.peStrike);
        isInitialLoad = true;
        lastUpdateTimestamp = null;
        loadInitialData();
    });
}

async function updateIncrementalData() {
    // Incremental update - only append new data points
    try {
        const newData = await fetchDataFromAPI();
        if (newData.length === 0) return;

        // Find new records since last update
        const lastTimestamp = lastUpdateTimestamp;
        const newRecords = lastTimestamp 
            ? newData.filter(row => row.timestamp > lastTimestamp)
            : newData;

        if (newRecords.length === 0) {
            console.log('No new data to update');
            updateLastUpdateTime();
            return;
        }

        console.log(`Updating ${newRecords.length} new data points`);

        // Update each new record
        for (const row of newRecords) {
            const t = toUnixSeconds(row.timestamp);
            if (!isFinite(t) || row.spot == null) continue;

            const vals = {
                spot: Number(row.spot),
                ce: Number(row.ce),
                pe: Number(row.pe),
                combinedPremium: row.combinedPremium != null ? Number(row.combinedPremium) : null,
                avgPrice: row.avgPrice != null ? Number(row.avgPrice) : null,
                ceIv: row.ceIV != null ? Number(row.ceIV) : null,
                peIv: row.peIV != null ? Number(row.peIV) : null,
                combinedIv: row.combinedIV != null ? Number(row.combinedIV) : null,
                ceExtrinsic: row.ceExtrinsic != null ? Number(row.ceExtrinsic) : null,
                peExtrinsic: row.peExtrinsic != null ? Number(row.peExtrinsic) : null,
                ceVwap: row.ceVwap != null ? Number(row.ceVwap) : null,
                peVwap: row.peVwap != null ? Number(row.peVwap) : null,
                combinedVwap: row.combinedVwap != null ? Number(row.combinedVwap) : null,
                cePrevClose: row.cePrevClose != null ? Number(row.cePrevClose) : null,
                pePrevClose: row.pePrevClose != null ? Number(row.pePrevClose) : null,
                combinedPrevClose: row.combinedPrevClose != null ? Number(row.combinedPrevClose) : null
            };

            if (!Number.isFinite(vals.spot)) continue;

            // Update series data
            if (lineVisibility.spot && Number.isFinite(vals.spot)) {
                lines.spot.update({ time: t, value: vals.spot });
                currentSeries.spotSeries.push({ time: t, value: vals.spot });
            }
            if (lineVisibility.ce && Number.isFinite(vals.ce)) {
                lines.ce.update({ time: t, value: vals.ce });
                currentSeries.ceSeries.push({ time: t, value: vals.ce });
            }
            if (lineVisibility.pe && Number.isFinite(vals.pe)) {
                lines.pe.update({ time: t, value: vals.pe });
                currentSeries.peSeries.push({ time: t, value: vals.pe });
            }
            if (lineVisibility.combinedPremium && vals.combinedPremium != null && Number.isFinite(vals.combinedPremium)) {
                lines.combinedPremium.update({ time: t, value: vals.combinedPremium });
                currentSeries.combinedPremiumSeries.push({ time: t, value: vals.combinedPremium });
            }
            if (lineVisibility.avgPrice && vals.avgPrice != null && Number.isFinite(vals.avgPrice)) {
                lines.avgPrice.update({ time: t, value: vals.avgPrice });
                currentSeries.avgPriceSeries.push({ time: t, value: vals.avgPrice });
            }
            if (lineVisibility.ceIv && vals.ceIv != null && Number.isFinite(vals.ceIv)) {
                lines.ceIv.update({ time: t, value: vals.ceIv });
                currentSeries.ceIvSeries.push({ time: t, value: vals.ceIv });
            }
            if (lineVisibility.peIv && vals.peIv != null && Number.isFinite(vals.peIv)) {
                lines.peIv.update({ time: t, value: vals.peIv });
                currentSeries.peIvSeries.push({ time: t, value: vals.peIv });
            }
            if (lineVisibility.combinedIv && vals.combinedIv != null && Number.isFinite(vals.combinedIv)) {
                lines.combinedIv.update({ time: t, value: vals.combinedIv });
                currentSeries.combinedIvSeries.push({ time: t, value: vals.combinedIv });
            }
            if (lineVisibility.ceExtrinsic && vals.ceExtrinsic != null && Number.isFinite(vals.ceExtrinsic)) {
                lines.ceExtrinsic.update({ time: t, value: vals.ceExtrinsic });
                currentSeries.ceExtrinsicSeries.push({ time: t, value: vals.ceExtrinsic });
            }
            if (lineVisibility.peExtrinsic && vals.peExtrinsic != null && Number.isFinite(vals.peExtrinsic)) {
                lines.peExtrinsic.update({ time: t, value: vals.peExtrinsic });
                currentSeries.peExtrinsicSeries.push({ time: t, value: vals.peExtrinsic });
            }
            if (lineVisibility.ceVwap && vals.ceVwap != null && Number.isFinite(vals.ceVwap)) {
                lines.ceVwap.update({ time: t, value: vals.ceVwap });
                currentSeries.ceVwapSeries.push({ time: t, value: vals.ceVwap });
            }
            if (lineVisibility.peVwap && vals.peVwap != null && Number.isFinite(vals.peVwap)) {
                lines.peVwap.update({ time: t, value: vals.peVwap });
                currentSeries.peVwapSeries.push({ time: t, value: vals.peVwap });
            }
            if (lineVisibility.combinedVwap && vals.combinedVwap != null && Number.isFinite(vals.combinedVwap)) {
                lines.combinedVwap.update({ time: t, value: vals.combinedVwap });
                currentSeries.combinedVwapSeries.push({ time: t, value: vals.combinedVwap });
            }
            if (lineVisibility.cePrevClose && vals.cePrevClose != null && Number.isFinite(vals.cePrevClose)) {
                lines.cePrevClose.update({ time: t, value: vals.cePrevClose });
                currentSeries.cePrevCloseSeries.push({ time: t, value: vals.cePrevClose });
            }
            if (lineVisibility.pePrevClose && vals.pePrevClose != null && Number.isFinite(vals.pePrevClose)) {
                lines.pePrevClose.update({ time: t, value: vals.pePrevClose });
                currentSeries.pePrevCloseSeries.push({ time: t, value: vals.pePrevClose });
            }
            if (lineVisibility.combinedPrevClose && vals.combinedPrevClose != null && Number.isFinite(vals.combinedPrevClose)) {
                lines.combinedPrevClose.update({ time: t, value: vals.combinedPrevClose });
                currentSeries.combinedPrevCloseSeries.push({ time: t, value: vals.combinedPrevClose });
            }
            // ADD THESE BLOCKS FOR THE NEW LINES:
            if (lineVisibility.cePrevLow && row.cePrevLow != null) {
                lines.cePrevLow.update({ time: t, value: Number(row.cePrevLow) });
                currentSeries.cePrevLowSeries.push({ time: t, value: Number(row.cePrevLow) });
            }
            if (lineVisibility.pePrevLow && row.pePrevLow != null) {
                lines.pePrevLow.update({ time: t, value: Number(row.pePrevLow) });
                currentSeries.pePrevLowSeries.push({ time: t, value: Number(row.pePrevLow) });
            }
            if (lineVisibility.combinedPrevLow && row.combinedPrevLow != null) {
                lines.combinedPrevLow.update({ time: t, value: Number(row.combinedPrevLow) });
                currentSeries.combinedPrevLowSeries.push({ time: t, value: Number(row.combinedPrevLow) });
            }
        }

        // Update last timestamp
        lastUpdateTimestamp = newRecords[newRecords.length - 1].timestamp;

        // Check for new crossovers
        if (crossoverEnabled && lineVisibility.ce && lineVisibility.pe && newRecords.length > 0) {
            const newCrossings = detectCrossovers(
                currentSeries.ceSeries.slice(-newRecords.length - 1),
                currentSeries.peSeries.slice(-newRecords.length - 1),
                'CE', 'PE'
            );
            if (newCrossings.length > 0) {
                allCrossings.push(...newCrossings);
                allCrossings.sort((a, b) => a.time - b.time);
                addCrossingMarkers();
                updateCrossingsDisplay();
            }
        }

        updateLegendLatest();
    } catch (err) {
        console.error('Error updating incremental data:', err);
    }
}

function startAutoRefresh() {
    if (autoRefreshInterval) return;
    
    // NEW: Do not start the interval if the tab is hidden
    if (document.hidden) return; 
    
    // Use incremental update for auto-refresh
    autoRefreshInterval = setInterval(() => updateIncrementalData(), 30000);
}

function stopAutoRefresh() {
    if (autoRefreshInterval) {
        clearInterval(autoRefreshInterval);
        autoRefreshInterval = null;
    }
}

function toggleTheme() {
    isDarkTheme = !isDarkTheme;
    const body = document.body;
    const sw = document.getElementById('theme-switch');
    const label = document.querySelector('#theme-toggle .toggle-label');

    if (isDarkTheme) {
        body.style.background = "linear-gradient(180deg,#071021 0%, #071a2b 100%)";
        sw.classList.add('active');
        label.textContent = 'Dark';
    } else {
        body.style.background = "linear-gradient(180deg,#f8fafc 0%, #e2e8f0 100%)";
        sw.classList.remove('active');
        label.textContent = 'Light';
    }
    updateChartTheme();
}

function toggleCrossover() {
    crossoverEnabled = !crossoverEnabled;
    const sw = document.getElementById('toggle-switch');

    if (crossoverEnabled) {
        sw.classList.add('active');
        addCrossingMarkers();
        updateCrossingsDisplay();
    } else {
        sw.classList.remove('active');
        clearCrossingMarkers();
        document.getElementById('crossings-container').innerHTML =
            '<div class="no-crossings">Enable "Crossovers" to detect CE-PE intersections.</div>';
    }
}

function toggleAutoUpdate() {
    autoUpdateEnabled = !autoUpdateEnabled;
    const label = document.getElementById('auto-update-label');
    const sw = document.getElementById('auto-update-switch');

    if (autoUpdateEnabled) {
        label.textContent = 'Auto: ON';
        sw.classList.add('active');
        startAutoRefresh();
    } else {
        label.textContent = 'Auto: OFF';
        sw.classList.remove('active');
        stopAutoRefresh();
    }
}

async function init() {
    loadLineVisibility();
    loadFullscreenState();
    
    // Apply fullscreen state immediately
    const card = document.getElementById('chart-card');
    if (isFullscreen) {
        card.classList.add('chart-fullscreen');
    }

    // Initialize pending state
    pendingLineVisibility = {...lineVisibility};

    document.getElementById('theme-toggle').addEventListener('click', toggleTheme);
    document.getElementById('toggle-container').addEventListener('click', toggleCrossover);
    document.getElementById('auto-update-toggle').addEventListener('click', toggleAutoUpdate);
    document.getElementById('btn-reset').addEventListener('click', () => {
        isInitialLoad = true; // Reset viewport on manual reset
        lastUpdateTimestamp = null; // Reset timestamp for full reload
        loadInitialData();
    });

    initCheckoutDropdown();
    syncCheckboxesFromVisibility();
    initDropdowns();
    await loadGroupedData();
    await loadInitialData();
    startAutoRefresh();

// ==========================================
    // NEW: Page Visibility API for Smart Polling
    // ==========================================
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            // Tab is inactive/hidden: pause the interval
            if (autoRefreshInterval) {
                clearInterval(autoRefreshInterval);
                autoRefreshInterval = null;
                console.log('Tab hidden: Auto-update paused');
            }
        } else {
            // Tab is active again: resume if the toggle is ON
            if (autoUpdateEnabled) {
                console.log('Tab active: Auto-update resumed');
                updateIncrementalData(); // Fetch immediately so user doesn't wait 30s
                startAutoRefresh();      // Restart the interval
            }
        }
    });
    // ==========================================
    window.addEventListener('resize', () => {
        const chartDiv = document.getElementById('chart');
        chart.applyOptions({ 
            width: chartDiv.clientWidth,
            height: chartDiv.clientHeight 
        });
    });
}

chart.subscribeCrosshairMove(param => updateLegendAtCrosshair(param));

(function setDefaultLightTheme() {
    isDarkTheme = false;

    document.body.style.background =
        "linear-gradient(180deg,#f8fafc 0%, #e2e8f0 100%)";

    const sw = document.getElementById("theme-switch");
    const label = document.querySelector("#theme-toggle .toggle-label");

    if (sw) sw.classList.remove("active");
    if (label) label.textContent = "Light";
})();

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}

window.addEventListener('beforeunload', stopAutoRefresh);