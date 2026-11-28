import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import {
  createChart,
  createSeriesMarkers,
  CrosshairMode,
  HistogramSeries,
  LineSeries,
  LineStyle,
} from 'lightweight-charts';
import './TrendDashboard.css';

// =========================================================================
// API Helpers
// =========================================================================
const API_ROOT = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');
const TREND_API = `${API_ROOT}/api/trend`;

const POLL_MS = 60_000;
const RADIUS_OPTIONS = [3, 5, 10, 0]; // Default ±3 (7); 0 = ALL (Full Chain)

const REGIME_BG = {
  dark: {
    BULLISH: 'rgba(52, 211, 153, 0.17)',
    BEARISH: 'rgba(251, 113, 133, 0.17)',
    SIDEWAYS: 'rgba(148, 163, 184, 0.09)',
  },
  light: {
    BULLISH: 'rgba(5, 150, 105, 0.14)',
    BEARISH: 'rgba(225, 29, 72, 0.13)',
    SIDEWAYS: 'rgba(100, 116, 139, 0.08)',
  },
};

async function handleJson(res, label) {
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`${label} failed (${res.status})${text ? `: ${text}` : ''}`);
  }
  return res.json();
}

async function fetchTrendInstruments(date = null, signal) {
  const params = new URLSearchParams();
  if (date) params.set('date', date);
  const qs = params.toString() ? `?${params.toString()}` : '';
  const res = await fetch(`${TREND_API}/instruments${qs}`, { signal });
  return handleJson(res, 'Load instruments');
}

async function fetchTrendChart(name, expiry, strikeRadius = null, date = null, signal) {
  const params = new URLSearchParams({ name, expiry });
  if (strikeRadius != null) params.set('strikeRadius', String(strikeRadius));
  if (date) params.set('date', date);
  const res = await fetch(`${TREND_API}/chart?${params.toString()}`, { signal });
  return handleJson(res, 'Load trend chart');
}

async function fetchTrendLatest(name, expiry, strikeRadius = null, signal) {
  const params = new URLSearchParams({ name, expiry });
  if (strikeRadius != null) params.set('strikeRadius', String(strikeRadius));
  const res = await fetch(`${TREND_API}/latest?${params.toString()}`, { signal });
  return handleJson(res, 'Load latest tick');
}

async function saveStrikeRadiusConfig(name, radius) {
  const params = new URLSearchParams({ name, radius: String(radius) });
  const res = await fetch(`${TREND_API}/config/radius?${params.toString()}`, {
    method: 'PUT',
  });
  return handleJson(res, 'Save strike radius');
}

// =========================================================================
// Formatting, Seller Action & Chart Helpers
// =========================================================================
function formatCompact(val) {
  if (val == null) return '-';
  const n = Number(val);
  const abs = Math.abs(n);
  const sign = n > 0 ? '+' : '';
  if (abs >= 1_000_000) return `${sign}${(n / 1_000_000).toFixed(2)}M`;
  if (abs >= 1_000) return `${sign}${(n / 1_000).toFixed(0)}K`;
  return `${sign}${n.toFixed(0)}`;
}

function formatNum(val, decimals = 1) {
  if (val == null) return '-';
  return Number(val).toLocaleString(undefined, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

function formatRadiusLabel(r) {
  return r === 0 ? 'ALL (Full Chain)' : `±${r} (${2 * r + 1})`;
}

function formatDurationMins(mins) {
  if (mins == null || mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

function getSellerAction(latest) {
  if (!latest) return null;
  const { confirmedState, pendingState, pendingMinutes, volRegime } = latest;

  if (pendingState && (pendingMinutes ?? 0) >= 5) {
    return {
      label: 'PAUSE — FLIP PENDING',
      tone: 'warn',
      detail: `Wait for ${pendingState} confirmation before fresh entry`,
    };
  }

  if (confirmedState === 'SIDEWAYS') {
    if (volRegime === 'EXPANSION_RISK') {
      return {
        label: 'WAIT — IV EXPANDING',
        tone: 'warn',
        detail: 'Spot is flat but premiums are expanding above VWAP',
      };
    }
    return {
      label: 'SELL STRADDLE',
      tone: 'bull',
      detail:
        volRegime === 'THETA_DECAY'
          ? 'Sideways + Theta Decay — ideal for both legs'
          : 'Sideways regime — neutral selling favored',
    };
  }

  if (confirmedState === 'BULLISH') {
    if (volRegime === 'EXPANSION_RISK') {
      return {
        label: 'NO SELL — BULL BREAKOUT',
        tone: 'bear',
        detail: 'Upside breakout + IV expansion — hedge/exit CE',
      };
    }
    return {
      label: 'SELL PE ONLY',
      tone: 'accent',
      detail: 'Controlled uptrend — avoid naked CE selling',
    };
  }

  if (confirmedState === 'BEARISH') {
    if (volRegime === 'EXPANSION_RISK') {
      return {
        label: 'NO SELL — BEAR BREAKOUT',
        tone: 'bear',
        detail: 'Downside breakout + IV expansion — hedge/exit PE',
      };
    }
    return {
      label: 'SELL CE ONLY',
      tone: 'accent',
      detail: 'Controlled downtrend — avoid naked PE selling',
    };
  }

  return { label: 'OBSERVE', tone: 'muted', detail: 'Waiting for regime clarity' };
}

function getSellerActionWithSince(timeline, latest) {
  const current = getSellerAction(latest);
  if (!current || !latest) return null;

  let sinceTime = latest.timeLabel || '--:--';
  let sinceEpoch = latest.epochSeconds ?? 0;

  if (Array.isArray(timeline) && timeline.length > 0) {
    for (let i = timeline.length - 1; i >= 0; i--) {
      const pt = timeline[i];
      if (!pt || (latest.epochSeconds != null && pt.epochSeconds > latest.epochSeconds)) {
        continue;
      }
      const ptAction = getSellerAction(pt);
      if (ptAction && ptAction.label === current.label) {
        sinceTime = pt.timeLabel || sinceTime;
        sinceEpoch = pt.epochSeconds ?? sinceEpoch;
      } else {
        break;
      }
    }
  }

  const ageMins =
    latest.epochSeconds != null && sinceEpoch > 0
      ? Math.max(0, Math.round((latest.epochSeconds - sinceEpoch) / 60))
      : 0;

  return {
    ...current,
    sinceTime,
    ageMins,
    ageLabel: formatDurationMins(ageMins),
    isNew: ageMins <= 15,
  };
}

function buildMarker(pt) {
  const isBull = pt.confirmedState === 'BULLISH';
  const isBear = pt.confirmedState === 'BEARISH';
  return {
    time: pt.epochSeconds,
    position: isBear ? 'aboveBar' : 'belowBar',
    color: isBull ? '#34d399' : isBear ? '#fb7185' : '#fbbf24',
    shape: isBull ? 'arrowUp' : isBear ? 'arrowDown' : 'circle',
    text: `${pt.transitionLabel || pt.confirmedState} (${pt.alignedStrikes}/${pt.totalStrikes})`,
  };
}

function dedupeSortedTimeline(timeline) {
  const byEpoch = new Map();
  for (const pt of timeline) {
    if (pt && pt.epochSeconds != null && pt.spot != null) {
      byEpoch.set(pt.epochSeconds, pt);
    }
  }
  return Array.from(byEpoch.values()).sort((a, b) => a.epochSeconds - b.epochSeconds);
}

// =========================================================================
// Lightweight Charts v5 Canvas Component
// =========================================================================
function TrendChartCanvas({
  timeline,
  latestIncrement,
  theme = 'dark',
  isFullscreen = false,
  consolidatedMessage,
  loading = false,
  loadingText = 'Loading trend chart...',
}) {
  const hostRef = useRef(null);
  const chartRef = useRef(null);
  const spotSeriesRef = useRef(null);
  const vwapSeriesRef = useRef(null);
  const regimeSeriesRef = useRef(null);
  const spotMarkersApiRef = useRef(null);
  const markersRef = useRef([]);
  const pointsByTimeRef = useRef(new Map());
  const lastEpochRef = useRef(0);

  const [hovered, setHovered] = useState(null);

  // Create chart ONCE per theme (autoSize handles fullscreen resize without wiping series)
  useEffect(() => {
    const el = hostRef.current;
    if (!el) return;

    const isDark = theme === 'dark';
    const chart = createChart(el, {
      autoSize: true,
      layout: {
        background: { color: isDark ? '#0b0f19' : '#ffffff' },
        textColor: isDark ? '#cbd5e1' : '#334155',
      },
      grid: {
        vertLines: { color: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(15,23,42,0.06)' },
        horzLines: { color: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(15,23,42,0.06)' },
      },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: {
        borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(15,23,42,0.12)',
        scaleMargins: { top: 0.16, bottom: 0.08 },
      },
      timeScale: {
        borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(15,23,42,0.12)',
        timeVisible: true,
        secondsVisible: false,
      },
    });

    const regimeSeries = chart.addSeries(HistogramSeries, {
      priceScaleId: 'regime',
      priceFormat: { type: 'volume' },
      lastValueVisible: false,
      priceLineVisible: false,
      autoscaleInfoProvider: () => ({
        priceRange: { minValue: 0, maxValue: 1 },
      }),
    });
    chart.priceScale('regime').applyOptions({
      scaleMargins: { top: 0, bottom: 0 },
      visible: false,
    });

    const vwapSeries = chart.addSeries(LineSeries, {
      color: '#fbbf24',
      lineWidth: 2,
      lineStyle: LineStyle.Dashed,
      title: 'Synth VWAP',
      crosshairMarkerVisible: true,
    });

    const spotSeries = chart.addSeries(LineSeries, {
      color: isDark ? '#f1f5f9' : '#0f172a',
      lineWidth: 2.5,
      title: 'Spot',
      crosshairMarkerVisible: true,
    });

    const spotMarkersApi = createSeriesMarkers(spotSeries, []);

    chart.subscribeCrosshairMove((param) => {
      if (!param || !param.time) {
        setHovered(null);
        return;
      }
      setHovered(pointsByTimeRef.current.get(param.time) || null);
    });

    chartRef.current = chart;
    regimeSeriesRef.current = regimeSeries;
    vwapSeriesRef.current = vwapSeries;
    spotSeriesRef.current = spotSeries;
    spotMarkersApiRef.current = spotMarkersApi;

    return () => {
      spotMarkersApiRef.current = null;
      chart.remove();
      chartRef.current = null;
    };
  }, [theme]);

  // Refit time axis cleanly after Full Chart toggle (preserves loaded data & markers)
  useEffect(() => {
    const timer = setTimeout(() => {
      chartRef.current?.timeScale().fitContent();
    }, 50);
    return () => clearTimeout(timer);
  }, [isFullscreen]);

  // Set full session timeline
  useEffect(() => {
    if (!spotSeriesRef.current) return;

    const clean = dedupeSortedTimeline(timeline || []);
    const lookup = new Map();
    const palette = REGIME_BG[theme] || REGIME_BG.dark;

    const spotData = [];
    const vwapData = [];
    const regimeData = [];
    const markers = [];

    for (const pt of clean) {
      lookup.set(pt.epochSeconds, pt);
      spotData.push({ time: pt.epochSeconds, value: Number(pt.spot) });
      vwapData.push({ time: pt.epochSeconds, value: Number(pt.syntheticVwap) });
      regimeData.push({
        time: pt.epochSeconds,
        value: 1,
        color: palette[pt.confirmedState] || palette.SIDEWAYS,
      });
      if (pt.stateChanged) {
        markers.push(buildMarker(pt));
      }
    }

    pointsByTimeRef.current = lookup;
    regimeSeriesRef.current.setData(regimeData);
    vwapSeriesRef.current.setData(vwapData);
    spotSeriesRef.current.setData(spotData);
    spotMarkersApiRef.current?.setMarkers(markers);

    markersRef.current = markers;
    lastEpochRef.current = clean.length > 0 ? clean[clean.length - 1].epochSeconds : 0;

    if (clean.length > 0) {
      chartRef.current?.timeScale().fitContent();
    }
  }, [timeline, theme]);

  // Append incremental 1-minute tick from /api/trend/latest
  useEffect(() => {
    if (!latestIncrement || !spotSeriesRef.current) return;
    if (latestIncrement.epochSeconds < lastEpochRef.current) return;

    const palette = REGIME_BG[theme] || REGIME_BG.dark;
    pointsByTimeRef.current.set(latestIncrement.epochSeconds, latestIncrement);

    spotSeriesRef.current.update({
      time: latestIncrement.epochSeconds,
      value: Number(latestIncrement.spot),
    });
    vwapSeriesRef.current.update({
      time: latestIncrement.epochSeconds,
      value: Number(latestIncrement.syntheticVwap),
    });
    regimeSeriesRef.current.update({
      time: latestIncrement.epochSeconds,
      value: 1,
      color: palette[latestIncrement.confirmedState] || palette.SIDEWAYS,
    });

    if (
      latestIncrement.stateChanged &&
      latestIncrement.epochSeconds > lastEpochRef.current
    ) {
      const nextMarkers = [...markersRef.current, buildMarker(latestIncrement)];
      markersRef.current = nextMarkers;
      spotMarkersApiRef.current?.setMarkers(nextMarkers);
    }

    lastEpochRef.current = latestIncrement.epochSeconds;
  }, [latestIncrement, theme]);

  const isEmpty = !loading && (!timeline || timeline.length === 0);

  return (
    <div className="st-chart-shell">
      <div className="st-chart-banner">
        <span className="st-chart-banner-main" title={consolidatedMessage}>
          {consolidatedMessage || (loading ? loadingText : 'Waiting for consolidated strike data...')}
        </span>
        {hovered && (
          <span className="st-chart-crosshair">
            {hovered.timeLabel} • Spot {Number(hovered.spot).toLocaleString()} • VWAP{' '}
            {Number(hovered.syntheticVwap).toLocaleString()} • {hovered.confirmedState} (
            {hovered.alignedStrikes}/{hovered.totalStrikes})
          </span>
        )}
      </div>

      {loading && (
        <div className="st-chart-overlay" role="status" aria-live="polite">
          <div className="st-spinner" />
          <span>{loadingText}</span>
        </div>
      )}

      {isEmpty && (
        <div className="st-chart-overlay">
          <span>No intraday straddle data available for this selection.</span>
        </div>
      )}

      <div ref={hostRef} className="st-chart-host" />
    </div>
  );
}

// =========================================================================
// Main Responsive Page
// =========================================================================
export default function TrendDashboard() {
  const outletCtx = useOutletContext();
  const theme = outletCtx?.theme || 'dark';

  const [isFullscreen, setIsFullscreen] = useState(false);
  const [tradeDate, setTradeDate] = useState('');
  const [instruments, setInstruments] = useState([]);
  const [selected, setSelected] = useState(null);
  const [strikeRadius, setStrikeRadius] = useState(3); // Default ±3 (7 strikes); 0 = ALL
  const [savedRadius, setSavedRadius] = useState(3);
  const [chartData, setChartData] = useState(null);
  const [latestTick, setLatestTick] = useState(null);

  const [loadingInstruments, setLoadingInstruments] = useState(true);
  const [loadingChart, setLoadingChart] = useState(false);
  const [savingRadius, setSavingRadius] = useState(false);

  const [isTabActive, setIsTabActive] = useState(!document.hidden);
  const [lastSyncTime, setLastSyncTime] = useState('');
  const [error, setError] = useState('');

  const pollTimerRef = useRef(null);
  const instAbortRef = useRef(null);
  const chartAbortRef = useRef(null);
  const selectedRef = useRef(null);

  useEffect(() => {
    selectedRef.current = selected;
  }, [selected]);

  // 1. Dynamically discover instruments from straddle_intraday
  const loadInstruments = useCallback(async (dateFilter) => {
    if (instAbortRef.current) instAbortRef.current.abort();
    const controller = new AbortController();
    instAbortRef.current = controller;

    setLoadingInstruments(true);
    setError('');
    try {
      const list = await fetchTrendInstruments(dateFilter || null, controller.signal);
      setInstruments(list);

      if (list.length === 0) {
        setSelected(null);
        setChartData(null);
        return;
      }

      const curr = selectedRef.current;
      const existing = curr
        ? list.find((i) => i.name === curr.name && i.expiry === curr.expiry)
        : null;

      if (!existing) {
        const first = list[0];
        const r = first.strikeRadius ?? 3;
        setLoadingChart(true);
        setSelected({ name: first.name, expiry: first.expiry });
        setStrikeRadius(r);
        setSavedRadius(r);
      }
    } catch (e) {
      if (e.name !== 'AbortError') setError(e.message);
    } finally {
      if (!controller.signal.aborted) {
        setLoadingInstruments(false);
      }
    }
  }, []);

  useEffect(() => {
    loadInstruments(tradeDate);
  }, [tradeDate, loadInstruments]);

  // 2. Load full chart on instrument, radius, or date change
  const loadFullChart = useCallback(async (sym, exp, radius, dateFilter) => {
    if (!sym || !exp) return;
    if (chartAbortRef.current) chartAbortRef.current.abort();
    const controller = new AbortController();
    chartAbortRef.current = controller;

    setLoadingChart(true);
    setError('');
    try {
      const data = await fetchTrendChart(sym, exp, radius, dateFilter || null, controller.signal);
      setChartData(data);
      setLatestTick(null);
      setLastSyncTime(
        new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      );
    } catch (e) {
      if (e.name !== 'AbortError') setError(e.message);
    } finally {
      if (!controller.signal.aborted) {
        setLoadingChart(false);
      }
    }
  }, []);

  useEffect(() => {
    if (selected) {
      loadFullChart(selected.name, selected.expiry, strikeRadius, tradeDate);
    }
  }, [selected, strikeRadius, tradeDate, loadFullChart]);

  // 3. Lightweight 1-minute poll ONLY while browser tab is visible/active
  const pollLatest = useCallback(async () => {
    if (document.hidden || !selected || tradeDate) return;
    try {
      const res = await fetchTrendLatest(selected.name, selected.expiry, strikeRadius);
      if (res?.latest) {
        setLatestTick(res.latest);
        setChartData((prev) => {
          if (!prev) return prev;
          const prevTimeline = prev.timeline || [];
          const lastPt = prevTimeline[prevTimeline.length - 1];
          const nextTimeline =
            lastPt && lastPt.epochSeconds === res.latest.epochSeconds
              ? [...prevTimeline.slice(0, -1), res.latest]
              : [...prevTimeline, res.latest];

          return {
            ...prev,
            consolidatedMessage: res.consolidatedMessage,
            latest: res.latest,
            timeline: nextTimeline,
            strikeBreakdown: res.strikeBreakdown,
          };
        });
        setInstruments((prev) =>
          prev.map((inst) =>
            inst.name === selected.name && inst.expiry === selected.expiry
              ? {
                  ...inst,
                  latestSpot: res.latest.spot,
                  latestState: res.latest.confirmedState,
                  consolidatedMessage: res.consolidatedMessage,
                }
              : inst
          )
        );
        setLastSyncTime(
          new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
        );
      }
    } catch {
      // Retry on next active minute
    }
  }, [selected, strikeRadius, tradeDate]);

  useEffect(() => {
    const startPolling = () => {
      clearInterval(pollTimerRef.current);
      if (!document.hidden && selected && !tradeDate) {
        pollTimerRef.current = setInterval(pollLatest, POLL_MS);
      }
    };

    const handleVisibility = () => {
      const visible = !document.hidden;
      setIsTabActive(visible);
      if (visible && selected && !tradeDate) {
        loadFullChart(selected.name, selected.expiry, strikeRadius, '');
        startPolling();
      } else {
        clearInterval(pollTimerRef.current);
      }
    };

    startPolling();
    document.addEventListener('visibilitychange', handleVisibility);
    return () => {
      clearInterval(pollTimerRef.current);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [selected, strikeRadius, tradeDate, pollLatest, loadFullChart]);

  const handleSelectInstrument = (inst) => {
    if (selected?.name === inst.name && selected?.expiry === inst.expiry) return;
    setLoadingChart(true);
    setSelected({ name: inst.name, expiry: inst.expiry });
    const r = inst.strikeRadius ?? 3;
    setStrikeRadius(r);
    setSavedRadius(r);
  };

  const handleSaveRadius = async () => {
    if (!selected) return;
    setSavingRadius(true);
    setError('');
    try {
      const res = await saveStrikeRadiusConfig(selected.name, strikeRadius);
      setSavedRadius(res.strikeRadius);
      setInstruments((prev) =>
        prev.map((inst) =>
          inst.name === selected.name
            ? { ...inst, strikeRadius: res.strikeRadius, totalStrikes: res.totalStrikes }
            : inst
        )
      );
      await loadFullChart(selected.name, selected.expiry, res.strikeRadius, tradeDate);
    } catch (e) {
      setError(e.message);
    } finally {
      setSavingRadius(false);
    }
  };

  const isBusy = loadingInstruments || loadingChart || savingRadius;
  const chartLoadingText = savingRadius
    ? 'Saving alert window & recalculating...'
    : loadingInstruments
    ? 'Loading active instruments...'
    : `Loading ${selected?.name || ''} trend chart...`;

  const latest = chartData?.latest;
  const sellerAction = getSellerActionWithSince(chartData?.timeline, latest);
  const stateClass = latest?.confirmedState || 'SIDEWAYS';
  const stateTxtClass =
    stateClass === 'BULLISH' ? 'st-bull' : stateClass === 'BEARISH' ? 'st-bear' : '';
  const volLabel = (latest?.volRegime || 'NEUTRAL_VOL').replaceAll('_', ' ');
  const activeRadius = chartData?.strikeRadius ?? strikeRadius;
  const radiusChoices = RADIUS_OPTIONS.includes(strikeRadius)
    ? RADIUS_OPTIONS
    : [...RADIUS_OPTIONS, strikeRadius];

  return (
    <div className={`st-page ${isFullscreen ? 'st-fullscreen' : ''}`}>
      {/* Control Bar */}
      <div className="st-glass st-toolbar">
        <div className="st-instrument-strip">
          {loadingInstruments && instruments.length === 0 && (
            <span className="st-loading-pill">
              <span className="st-spinner-sm" />
              Loading instruments...
            </span>
          )}
          {!loadingInstruments && instruments.length === 0 && (
            <span className="st-muted" style={{ fontSize: 12.5 }}>
              No active instruments in straddle_intraday
            </span>
          )}
          {instruments.map((inst) => {
            const active = selected?.name === inst.name && selected?.expiry === inst.expiry;
            const st = inst.latestState || 'SIDEWAYS';
            return (
              <button
                key={`${inst.name}-${inst.expiry}`}
                type="button"
                className={`st-pill ${active ? `active state-${st}` : ''}`}
                onClick={() => handleSelectInstrument(inst)}
              >
                <span className={`st-dot ${st}`} />
                <span>{inst.name}</span>
                <span className="st-pill-exp">{inst.expiry}</span>
              </button>
            );
          })}
        </div>

        <div className="st-controls">
          {isFullscreen && sellerAction && (
            <span className={`st-action-pill tone-${sellerAction.tone}`} title={sellerAction.detail}>
              {sellerAction.label} • Since {sellerAction.sinceTime} ({sellerAction.ageLabel})
            </span>
          )}

          <label className="st-ctrl-label">
            <span>Strikes</span>
            <select
              className="st-input"
              value={strikeRadius}
              onChange={(e) => setStrikeRadius(Number(e.target.value))}
              disabled={isBusy}
            >
              {radiusChoices.map((r) => (
                <option key={r} value={r}>
                  {formatRadiusLabel(r)}
                </option>
              ))}
            </select>
          </label>

          {strikeRadius !== savedRadius && !tradeDate && (
            <button
              type="button"
              className="st-btn st-btn-primary"
              onClick={handleSaveRadius}
              disabled={savingRadius}
            >
              {savingRadius ? 'Saving...' : 'Save for Alerts'}
            </button>
          )}

          <input
            type="date"
            className="st-input st-hide-mobile"
            value={tradeDate}
            onChange={(e) => setTradeDate(e.target.value)}
            title="Replay a specific trading date (blank = latest session)"
          />

          <button
            type="button"
            className={`st-btn ${isBusy ? 'st-btn-spin' : ''}`}
            onClick={() =>
              selected
                ? loadFullChart(selected.name, selected.expiry, strikeRadius, tradeDate)
                : loadInstruments(tradeDate)
            }
            disabled={isBusy}
            title="Refresh now"
          >
            ↻
          </button>

          <span className={`st-live-badge ${isTabActive && !tradeDate ? 'active' : 'paused'}`}>
            {tradeDate ? 'Replay' : isTabActive ? '● Live 1m' : '⏸ Paused'}
          </span>

          <button
            type="button"
            className="st-btn"
            onClick={() => setIsFullscreen((f) => !f)}
          >
            {isFullscreen ? 'Exit Full' : 'Full Chart'}
          </button>
        </div>
      </div>

      {error && (
        <div className="st-error">
          <span>{error}</span>
          <button type="button" className="st-btn" onClick={() => setError('')}>
            ✕
          </button>
        </div>
      )}

      {/* KPI Strip (Seller Action + 4 Core Signals) */}
      {!isFullscreen && latest && (
        <section className={`st-kpi-grid ${isBusy ? 'st-dimmed' : ''}`}>
          {sellerAction && (
            <div className={`st-glass st-kpi-card st-action-card tone-${sellerAction.tone}`}>
              <span className="st-kpi-label">
                Seller Action • Since {sellerAction.sinceTime} ({sellerAction.ageLabel})
                {sellerAction.isNew && <span className="st-new-tag">NEW</span>}
              </span>
              <span className="st-kpi-value">
                <span className={`st-action-pill tone-${sellerAction.tone}`}>
                  {sellerAction.label}
                </span>
              </span>
              <span className="st-kpi-sub" title={sellerAction.detail}>
                {sellerAction.detail}
              </span>
            </div>
          )}

          <div className="st-glass st-kpi-card">
            <span className="st-kpi-label">Confirmed Trend</span>
            <span className={`st-kpi-value ${stateTxtClass}`}>
              {latest.confirmedState}
              {latest.pendingState && (
                <span className="st-warn" style={{ fontSize: 11 }}>
                  ⏳ → {latest.pendingState} ({Math.floor(latest.pendingMinutes)}m/
                  {chartData?.confirmMinutes ?? 15}m)
                </span>
              )}
            </span>
            <span className="st-kpi-sub">
              Spot {formatNum(latest.spot, 1)} • ATM {formatNum(latest.atmStrike, 0)} •{' '}
              {chartData?.tradeDate} {latest.timeLabel}
            </span>
          </div>

          <div className="st-glass st-kpi-card">
            <span className="st-kpi-label">
              VWAP Consensus ({activeRadius === 0 ? 'ALL' : `±${activeRadius}`})
            </span>
            <span
              className={`st-kpi-value ${
                latest.priceSignal === 'BEARISH'
                  ? 'st-bear'
                  : latest.priceSignal === 'BULLISH'
                  ? 'st-bull'
                  : ''
              }`}
            >
              {latest.alignedStrikes}/{latest.totalStrikes} {latest.priceSignal}
            </span>
            <span className="st-kpi-sub">Synth VWAP {formatNum(latest.syntheticVwap, 1)}</span>
          </div>

          <div className="st-glass st-kpi-card">
            <span className="st-kpi-label">OI Walls &amp; Flow</span>
            <span className="st-kpi-value">
              <span className="st-bear">CE {formatCompact(latest.ceWall)}</span>
              <span className="st-muted" style={{ fontSize: 11.5 }}>
                vs
              </span>
              <span className="st-bull">PE {formatCompact(latest.peWall)}</span>
            </span>
            <span className="st-kpi-sub">
              Wall: {latest.oiWallSignal} • Flow: {latest.oiFlowSignal}
            </span>
          </div>

          <div className="st-glass st-kpi-card">
            <span className="st-kpi-label">Volatility Overlay</span>
            <span
              className={`st-kpi-value ${
                latest.volRegime === 'EXPANSION_RISK'
                  ? 'st-warn'
                  : latest.volRegime === 'THETA_DECAY'
                  ? 'st-bull'
                  : ''
              }`}
            >
              {volLabel}
            </span>
            <span className="st-kpi-sub">
              ATM Comb vs VWAP: {latest.atmCombDiffPct > 0 ? '+' : ''}
              {latest.atmCombDiffPct}% {lastSyncTime ? `• ${lastSyncTime}` : ''}
            </span>
          </div>
        </section>
      )}

      {/* Main Responsive Chart */}
      <TrendChartCanvas
        timeline={chartData?.timeline || []}
        latestIncrement={latestTick}
        theme={theme}
        isFullscreen={isFullscreen}
        consolidatedMessage={chartData?.consolidatedMessage}
        loading={isBusy}
        loadingText={chartLoadingText}
      />

      {/* Responsive Strike Breakdown Table */}
      {!isFullscreen && chartData?.strikeBreakdown?.length > 0 && (
        <section className={`st-glass st-strike-section ${isBusy ? 'st-dimmed' : ''}`}>
          <div className="st-strike-header">
            <strong>
              {chartData.name} ({chartData.expiry}) —{' '}
              {activeRadius === 0 ? 'ALL Strikes' : `ATM ±${activeRadius}`} (
              {chartData.strikeBreakdown.length} Strikes)
            </strong>
            <span className={`st-state-pill ${stateClass}`}>
              {stateClass} • {volLabel}
            </span>
          </div>

          <div className="st-table-wrap">
            <table className="st-table">
              <thead>
                <tr>
                  <th>Strike</th>
                  <th>CE / VWAP</th>
                  <th>PE / VWAP</th>
                  <th>VWAP Read</th>
                  <th>CE OI (Δ)</th>
                  <th>PE OI (Δ)</th>
                  <th>OI Lead</th>
                </tr>
              </thead>
              <tbody>
                {chartData.strikeBreakdown.map((row) => {
                  const isAtm = row.zone === 'ATM';
                  const sigClass =
                    row.priceSignal === 'BEARISH'
                      ? 'st-bear'
                      : row.priceSignal === 'BULLISH'
                      ? 'st-bull'
                      : 'st-muted';
                  const oiDiffNum = Number(row.oiDiff || 0);

                  return (
                    <tr key={row.strike} className={isAtm ? 'is-atm' : ''}>
                      <td>
                        <span className={`st-zone ${row.zone}`}>
                          {row.zone === 'RESISTANCE'
                            ? 'RES'
                            : row.zone === 'SUPPORT'
                            ? 'SUP'
                            : 'ATM'}
                        </span>
                        {formatNum(row.strike, 0)}
                      </td>
                      <td>
                        {formatNum(row.cePrice, 1)}{' '}
                        <span className="st-muted">/ {formatNum(row.ceVwap, 1)}</span>
                      </td>
                      <td>
                        {formatNum(row.pePrice, 1)}{' '}
                        <span className="st-muted">/ {formatNum(row.peVwap, 1)}</span>
                      </td>
                      <td className={sigClass} style={{ fontWeight: 700 }}>
                        {row.priceSignal}
                      </td>
                      <td>
                        {formatCompact(row.ceOi)}{' '}
                        <span className="st-muted">({formatCompact(row.ceOiChange)})</span>
                      </td>
                      <td>
                        {formatCompact(row.peOi)}{' '}
                        <span className="st-muted">({formatCompact(row.peOiChange)})</span>
                      </td>
                      <td
                        className={oiDiffNum >= 0 ? 'st-bear' : 'st-bull'}
                        style={{ fontWeight: 700 }}
                      >
                        {oiDiffNum >= 0 ? 'CE ' : 'PE '}
                        {formatCompact(Math.abs(oiDiffNum))}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}