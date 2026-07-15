import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import api from '../services/api'; // Reuses your configured Vite proxy / Axios setup
import './OITracker.css';

// Tunable constants — calibrate against your own backtest data.
const MIN_DELTA_FOR_PCR = 5000;      // ΔCE OI below this = too noisy to trust as a divisor
const CONVICTION_THRESHOLD = 20000;  // |ΔCombined OI (smoothed)| above this = "active" session

// Measures a container's pixel size and keeps it in sync on resize,
// so the SVG viewBox matches the real rendered area 1:1 (needed for accurate hover mapping
// and, critically, so the chart isn't drawn taller than the space actually visible to it).
function useElementSize() {
  const ref = useRef(null);
  const [size, setSize] = useState({ width: 900, height: 380 });
  useEffect(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { width, height } = entry.contentRect;
        if (width > 0 && height > 0) setSize({ width, height });
      }
    });
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, []);
  return [ref, size];
}

export default function OITracker() {
  const [instrument, setInstrument] = useState('NIFTY');
  const [strikes, setStrikes] = useState([]);
  const [selectedStrike, setSelectedStrike] = useState(null);
  const [activeTab, setActiveTab] = useState('OI'); // 'OI' | 'PCR'
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [loading, setLoading] = useState(true);
  const [smoothWindow, setSmoothWindow] = useState(3);
  const [hoverIndex, setHoverIndex] = useState(null);

  // latestChain is fetched only to auto-detect the ATM strike and populate the strike list.
  // It is not rendered — a full option-chain view doesn't help an intraday decision.
  const [latestChain, setLatestChain] = useState([]);
  const [timeSeries, setTimeSeries] = useState([]);

  // IMPORTANT: this ref/size measures ONLY the plot area (the div that holds the <svg>),
  // not the outer panel that also contains the legend. Measuring the outer panel and then
  // drawing the SVG at that same height is what caused the bottom of the chart to be cut off —
  // the legend was eating into space the SVG thought it owned.
  const [plotRef, plotSize] = useElementSize();

  useEffect(() => {
    let isMounted = true;
    const fetchInitialData = async () => {
      setLoading(true);
      try {
        const [strikesRes, chainRes] = await Promise.all([
          api.get(`/api/oi/strikes/${instrument}`),
          api.get(`/api/oi/latest/${instrument}?_t=${Date.now()}`)
        ]);
        if (!isMounted) return;

        const strikeList = Array.isArray(strikesRes.data) ? strikesRes.data : [];
        let chainData = [];
        if (Array.isArray(chainRes.data)) {
          chainData = chainRes.data;
        } else if (chainRes.data && typeof chainRes.data === 'object') {
          chainData = chainRes.data.content || chainRes.data.list || [];
        }

        setStrikes(strikeList);
        setLatestChain(chainData);

        const atmRecord = chainData.find(item => item.isATM === true);
        if (atmRecord && atmRecord.strike) {
          setSelectedStrike(atmRecord.strike);
        } else if (strikeList.length > 0) {
          setSelectedStrike(strikeList[Math.floor(strikeList.length / 2)]);
        }
      } catch (error) {
        console.error("Failed to fetch initial OI data:", error);
      } finally {
        if (isMounted) setLoading(false);
      }
    };
    fetchInitialData();
    return () => { isMounted = false; };
  }, [instrument]);

  useEffect(() => {
    if (!selectedStrike) return;
    let isMounted = true;

    const fetchStrikeSeries = async () => {
      try {
        const res = await api.get(`/api/oi/strike/${instrument}/${selectedStrike}?_t=${Date.now()}`);
        if (!isMounted) return;
        let seriesData = [];
        if (Array.isArray(res.data)) {
          seriesData = res.data;
        } else if (res.data && typeof res.data === 'object') {
          seriesData = res.data.content || res.data.data || res.data.list || [];
        }
        setTimeSeries(seriesData);
      } catch (error) {
        console.error(`Failed to fetch strike data for ${selectedStrike}:`, error);
        if (isMounted) setTimeSeries([]);
      }
    };

    fetchStrikeSeries();
    let intervalId = null;
    if (autoRefresh) {
      intervalId = setInterval(() => {
        fetchStrikeSeries();
        api.get(`/api/oi/latest/${instrument}`).then(r => {
          const latestData = Array.isArray(r.data) ? r.data : (r.data?.content || r.data?.list || []);
          setLatestChain(latestData);
        });
      }, 30000);
    }
    return () => {
      isMounted = false;
      if (intervalId) clearInterval(intervalId);
    };
  }, [instrument, selectedStrike, autoRefresh]);

  // Dedupe repeated timestamps (the feed sometimes returns the same tick 2-10x in a row)
  // and sort ascending, so rolling-window math isn't diluted by duplicates.
  const dedupedSeries = useMemo(() => {
    if (!timeSeries || timeSeries.length === 0) return [];
    const map = new Map();
    timeSeries.forEach(row => { map.set(row.timestamp, row); });
    return Array.from(map.values()).sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
  }, [timeSeries]);

  // Raw ΔOI, smoothed ΔOI (rolling sum over smoothWindow points), absolute PCR, and delta-PCR.
  const derivedSeries = useMemo(() => {
    return dedupedSeries.map((row, i) => {
      const ceChange = Number(row.ceOiChange || 0);
      const peChange = Number(row.peOiChange || 0);
      const combinedChange = ceChange + peChange;

      const start = Math.max(0, i - smoothWindow + 1);
      let ceSmooth = 0, peSmooth = 0;
      for (let j = start; j <= i; j++) {
        ceSmooth += Number(dedupedSeries[j].ceOiChange || 0);
        peSmooth += Number(dedupedSeries[j].peOiChange || 0);
      }
      const combinedSmooth = ceSmooth + peSmooth;

      const ceOi = Number(row.ceOi || 0);
      const peOi = Number(row.peOi || 0);
      const absolutePcr = ceOi > 0 ? peOi / ceOi : null;
      const deltaPcr = Math.abs(ceSmooth) >= MIN_DELTA_FOR_PCR ? peSmooth / ceSmooth : null;

      return { ...row, ceChange, peChange, combinedChange, ceSmooth, peSmooth, combinedSmooth, absolutePcr, deltaPcr };
    });
  }, [dedupedSeries, smoothWindow]);

  const latestDerived = derivedSeries.length > 0 ? derivedSeries[derivedSeries.length - 1] : null;

  const chainPcr = useMemo(() => {
    if (!latestChain || latestChain.length === 0) return { pcr: '0.00', sentiment: 'NEUTRAL' };
    let totalCe = 0, totalPe = 0;
    latestChain.forEach(row => {
      totalCe += Number(row.ceOi || 0);
      totalPe += Number(row.peOi || 0);
    });
    const pcr = totalCe > 0 ? (totalPe / totalCe) : 0;
    let sentiment = 'NEUTRAL';
    if (pcr > 1.1) sentiment = 'BULLISH';
    else if (pcr < 0.9) sentiment = 'BEARISH';
    return { pcr: pcr.toFixed(2), sentiment };
  }, [latestChain]);

  const freshFlow = useMemo(() => {
    if (!latestDerived) return { deltaPcr: null, conviction: 'NEUTRAL', skew: '—' };
    const conviction = Math.abs(latestDerived.combinedSmooth) >= CONVICTION_THRESHOLD ? 'ACTIVE' : 'QUIET';
    let skew = 'BALANCED';
    if (latestDerived.deltaPcr === null) skew = 'LOW DATA';
    else if (latestDerived.deltaPcr > 1.15) skew = 'PUT-HEAVY';
    else if (latestDerived.deltaPcr < 0.85) skew = 'CALL-HEAVY';
    return { deltaPcr: latestDerived.deltaPcr, conviction, skew };
  }, [latestDerived]);

  // Map a mouse event's X position (relative to the plot area) to the nearest data index.
  const handleHover = useCallback((e, padding, plotWidth) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const ratio = (x - padding) / plotWidth;
    const idx = Math.round(ratio * (derivedSeries.length - 1));
    setHoverIndex(Math.max(0, Math.min(derivedSeries.length - 1, idx)));
  }, [derivedSeries.length]);

  const clearHover = useCallback(() => setHoverIndex(null), []);

  const formatTime = (ts) => {
    if (!ts) return '';
    const t = ts.includes('T') ? ts.split('T')[1] : ts;
    return t.substring(0, 8);
  };

  const fmtK = (v) => {
    const sign = v > 0 ? '+' : '';
    return `${sign}${Math.round(v / 1000)}k`;
  };

  // --- CHART: ΔCE OI / ΔPE OI / ΔCombined OI ---
  const renderOiChart = () => {
    if (!derivedSeries || derivedSeries.length < 2) {
      return <div className="no-data">Not enough data yet for {selectedStrike}.</div>;
    }
    const { width, height } = plotSize;
    const padding = 46;
    const plotWidth = width - padding * 2;

    const ceChanges = derivedSeries.map(d => d.ceChange);
    const peChanges = derivedSeries.map(d => d.peChange);
    const combinedChanges = derivedSeries.map(d => d.combinedChange);
    const allValues = [...ceChanges, ...peChanges, ...combinedChanges];
    const maxVal = Math.max(...allValues, 1000);
    const minVal = Math.min(...allValues, -1000);
    const range = maxVal - minVal || 1;

    const getX = (i) => padding + (i / (derivedSeries.length - 1)) * plotWidth;
    const getY = (v) => height - padding - ((v - minVal) / range) * (height - padding * 2);
    const zeroY = getY(0);
    const buildPath = (arr) => arr.map((v, i) => `${i === 0 ? 'M' : 'L'} ${getX(i)} ${getY(v)}`).join(' ');

    const hovered = hoverIndex !== null ? derivedSeries[hoverIndex] : null;
    const tooltipLeft = hoverIndex !== null ? getX(hoverIndex) : 0;
    const flipTooltip = tooltipLeft > width - 190;

    return (
      <>
        <div
          className="plot-area"
          ref={plotRef}
          onMouseMove={(e) => handleHover(e, padding, plotWidth)}
          onMouseLeave={clearHover}
        >
          <svg width={width} height={height} className="svg-canvas">
            <line x1={padding} y1={zeroY} x2={width - padding} y2={zeroY} className="axis-zero" />
            <line x1={padding} y1={padding} x2={padding} y2={height - padding} className="axis-line" />

            <text x={padding - 8} y={getY(maxVal)} className="axis-label" textAnchor="end">{fmtK(maxVal)}</text>
            <text x={padding - 8} y={zeroY} className="axis-label" textAnchor="end">0</text>
            <text x={padding - 8} y={getY(minVal)} className="axis-label" textAnchor="end">{fmtK(minVal)}</text>

            <path d={buildPath(combinedChanges)} className="line-combined" />
            <path d={buildPath(ceChanges)} className="line-ce" />
            <path d={buildPath(peChanges)} className="line-pe" />

            <text x={getX(0)} y={height - 10} className="axis-label" textAnchor="start">
              {formatTime(derivedSeries[0]?.timestamp)}
            </text>
            <text x={getX(derivedSeries.length - 1)} y={height - 10} className="axis-label" textAnchor="end">
              {formatTime(derivedSeries[derivedSeries.length - 1]?.timestamp)}
            </text>

            {hoverIndex !== null && (
              <>
                <line x1={getX(hoverIndex)} y1={padding} x2={getX(hoverIndex)} y2={height - padding} className="crosshair" />
                <circle cx={getX(hoverIndex)} cy={getY(hovered.ceChange)} r="4" className="dot-ce" />
                <circle cx={getX(hoverIndex)} cy={getY(hovered.peChange)} r="4" className="dot-pe" />
                <circle cx={getX(hoverIndex)} cy={getY(hovered.combinedChange)} r="3.5" className="dot-combined" />
              </>
            )}
          </svg>

          {hovered && (
            <div className={`chart-tooltip ${flipTooltip ? 'flip' : ''}`} style={{ left: tooltipLeft, top: 10 }}>
              <div className="tooltip-time">{formatTime(hovered.timestamp)}</div>
              <div className="tooltip-row"><span className="sw ce" />ΔCE OI<b>{fmtK(hovered.ceChange)}</b></div>
              <div className="tooltip-row"><span className="sw pe" />ΔPE OI<b>{fmtK(hovered.peChange)}</b></div>
              <div className="tooltip-row"><span className="sw combined" />ΔCombined<b>{fmtK(hovered.combinedChange)}</b></div>
            </div>
          )}
        </div>

        <div className="chart-legend">
          <span className="legend-item"><i className="swatch line-ce" />Red = ΔCE OI (calls)</span>
          <span className="legend-item"><i className="swatch line-pe" />Green = ΔPE OI (puts)</span>
          <span className="legend-item"><i className="swatch line-combined dashed" />Amber dashed = ΔCombined OI</span>
        </div>
      </>
    );
  };

  // --- CHART: Absolute PCR vs Delta PCR ---
  const renderPcrChart = () => {
    if (!derivedSeries || derivedSeries.length < 2) {
      return <div className="no-data">Not enough data yet for {selectedStrike}.</div>;
    }
    const { width, height } = plotSize;
    const padding = 46;
    const plotWidth = width - padding * 2;

    const absValues = derivedSeries.map(d => d.absolutePcr).filter(v => v !== null && isFinite(v));
    const deltaValues = derivedSeries.map(d => d.deltaPcr).filter(v => v !== null && isFinite(v));
    const allValues = [...absValues, ...deltaValues, 1];
    const maxVal = Math.max(...allValues) * 1.1;
    const minVal = Math.min(...allValues) * 0.9;
    const range = maxVal - minVal || 1;

    const getX = (i) => padding + (i / (derivedSeries.length - 1)) * plotWidth;
    const getY = (v) => height - padding - ((v - minVal) / range) * (height - padding * 2);
    const neutralY = getY(1);

    const buildPathWithGaps = (values) => {
      let d = '', penDown = false;
      values.forEach((v, i) => {
        if (v === null || !isFinite(v)) { penDown = false; return; }
        d += `${!penDown ? 'M' : 'L'} ${getX(i)} ${getY(v)} `;
        penDown = true;
      });
      return d.trim();
    };
    const absPath = buildPathWithGaps(derivedSeries.map(d => d.absolutePcr));
    const deltaPath = buildPathWithGaps(derivedSeries.map(d => d.deltaPcr));

    const hovered = hoverIndex !== null ? derivedSeries[hoverIndex] : null;
    const tooltipLeft = hoverIndex !== null ? getX(hoverIndex) : 0;
    const flipTooltip = tooltipLeft > width - 190;

    return (
      <>
        <div
          className="plot-area"
          ref={plotRef}
          onMouseMove={(e) => handleHover(e, padding, plotWidth)}
          onMouseLeave={clearHover}
        >
          <svg width={width} height={height} className="svg-canvas">
            <line x1={padding} y1={neutralY} x2={width - padding} y2={neutralY} className="axis-zero" />
            <line x1={padding} y1={padding} x2={padding} y2={height - padding} className="axis-line" />

            <text x={padding - 8} y={getY(maxVal)} className="axis-label" textAnchor="end">{maxVal.toFixed(2)}</text>
            <text x={padding - 8} y={neutralY} className="axis-label" textAnchor="end">1.00</text>
            <text x={padding - 8} y={getY(minVal)} className="axis-label" textAnchor="end">{minVal.toFixed(2)}</text>

            <path d={absPath} className="line-abs-pcr" />
            <path d={deltaPath} className="line-delta-pcr" />

            <text x={getX(0)} y={height - 10} className="axis-label" textAnchor="start">
              {formatTime(derivedSeries[0]?.timestamp)}
            </text>
            <text x={getX(derivedSeries.length - 1)} y={height - 10} className="axis-label" textAnchor="end">
              {formatTime(derivedSeries[derivedSeries.length - 1]?.timestamp)}
            </text>

            {hoverIndex !== null && (
              <>
                <line x1={getX(hoverIndex)} y1={padding} x2={getX(hoverIndex)} y2={height - padding} className="crosshair" />
                {hovered.absolutePcr !== null && <circle cx={getX(hoverIndex)} cy={getY(hovered.absolutePcr)} r="4" className="dot-abs-pcr" />}
                {hovered.deltaPcr !== null && <circle cx={getX(hoverIndex)} cy={getY(hovered.deltaPcr)} r="4" className="dot-delta-pcr" />}
              </>
            )}
          </svg>

          {hovered && (
            <div className={`chart-tooltip ${flipTooltip ? 'flip' : ''}`} style={{ left: tooltipLeft, top: 10 }}>
              <div className="tooltip-time">{formatTime(hovered.timestamp)}</div>
              <div className="tooltip-row"><span className="sw abs" />Absolute PCR<b>{hovered.absolutePcr !== null ? hovered.absolutePcr.toFixed(2) : '—'}</b></div>
              <div className="tooltip-row"><span className="sw delta" />Delta PCR<b>{hovered.deltaPcr !== null ? hovered.deltaPcr.toFixed(2) : 'low data'}</b></div>
            </div>
          )}
        </div>

        <div className="chart-legend">
          <span className="legend-item"><i className="swatch line-abs-pcr" />amber solid = Absolute PCR</span>
          <span className="legend-item"><i className="swatch line-delta-pcr dashed" />violet dashed = Delta PCR (gap = below trust threshold)</span>
        </div>
      </>
    );
  };

  return (
    <div className="oi-tracker-container">
      {/* Toolbar — single compact row, wraps on narrow screens */}
      <div className="toolbar">
        <div className="instrument-toggle">
          <button className={`toggle-btn ${instrument === 'NIFTY' ? 'active' : ''}`} onClick={() => setInstrument('NIFTY')}>NIFTY</button>
          <button className={`toggle-btn ${instrument === 'CRUDEOIL' ? 'active' : ''}`} onClick={() => setInstrument('CRUDEOIL')}>CRUDE OIL</button>
        </div>

        <select
          className="control-select"
          value={selectedStrike || ''}
          onChange={(e) => setSelectedStrike(Number(e.target.value))}
        >
          {strikes.map((stk) => (
            <option key={stk} value={stk}>
              {stk}{latestChain.find(c => Number(c.strike) === Number(stk) && (c.isATM || c.isAtm)) ? ' · ATM' : ''}
            </option>
          ))}
        </select>

        <select
          className="control-select"
          value={smoothWindow}
          onChange={(e) => setSmoothWindow(Number(e.target.value))}
          title="OI smoothing window"
        >
          <option value={1}>1 min</option>
          <option value={3}>3 min</option>
          <option value={5}>5 min</option>
        </select>

        <label className="refresh-toggle">
          <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
          <span className={`live-dot ${loading ? 'syncing' : ''}`} />
          Live
        </label>
      </div>

      {/* Status row — compact KPI chips + chart switch, single line */}
      <div className="status-row">
        <div className="kpi-chips">
          <div className="chip">
            <span className="chip-label">Chain PCR</span>
            <span className={`chip-value ${chainPcr.sentiment.toLowerCase()}`}>{chainPcr.pcr}</span>
          </div>
          <div className="chip">
            <span className="chip-label">Δ-PCR ({selectedStrike})</span>
            <span className="chip-value">{freshFlow.deltaPcr !== null ? freshFlow.deltaPcr.toFixed(2) : '—'}</span>
            <span className={`chip-tag ${freshFlow.conviction === 'ACTIVE' ? 'active' : 'quiet'}`}>{freshFlow.conviction}</span>
          </div>
          <div className="chip">
            <span className="chip-label">Skew</span>
            <span className="chip-value skew">{freshFlow.skew}</span>
          </div>
        </div>

        <div className="tab-switch">
          <button className={`tab-btn ${activeTab === 'OI' ? 'active' : ''}`} onClick={() => { setActiveTab('OI'); setHoverIndex(null); }}>OI Momentum</button>
          <button className={`tab-btn ${activeTab === 'PCR' ? 'active' : ''}`} onClick={() => { setActiveTab('PCR'); setHoverIndex(null); }}>PCR Skew</button>
        </div>
      </div>

      {/* Chart panel — plot area fills remaining space, legend sits below it with its own fixed space */}
      <div className="chart-container">
        <div className="chart-surface">
          {activeTab === 'OI' ? renderOiChart() : renderPcrChart()}
        </div>
      </div>
    </div>
  );
}
