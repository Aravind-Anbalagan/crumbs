import React, { useState, useEffect, useLayoutEffect, useMemo, useCallback, useRef } from 'react';
import api from '../services/api';
import './OITracker.css';

const AXIS_W = 64;        // fixed Y-axis column width
const SCROLLBAR_H = 12;   // room for the thin scrollbar
const MIN_PX = 2;
const MAX_PX = 40;
const DEFAULT_PX = 8;

function useElementSize() {
  const [node, setNode] = useState(null);
  const [size, setSize] = useState({ width: 800, height: 400 });
  const setRef = useCallback((el) => setNode(el), []);

  useEffect(() => {
    if (!node) return;
    const ro = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      if (width > 0 && height > 0) setSize({ width, height });
    });
    ro.observe(node);
    const rect = node.getBoundingClientRect();
    if (rect.width > 0 && rect.height > 0) setSize({ width: rect.width, height: rect.height });
    return () => ro.disconnect();
  }, [node]);

  return [setRef, size];
}

function smoothPath(points) {
  if (points.length < 2) return '';
  if (points.length === 2) return `M ${points[0].x} ${points[0].y} L ${points[1].x} ${points[1].y}`;
  let d = `M ${points[0].x} ${points[0].y}`;
  for (let i = 0; i < points.length - 1; i++) {
    const p0 = points[i - 1] || points[i];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = points[i + 2] || p2;
    d += ` C ${p1.x + (p2.x - p0.x) / 6} ${p1.y + (p2.y - p0.y) / 6}, ${p2.x - (p3.x - p1.x) / 6} ${p2.y - (p3.y - p1.y) / 6}, ${p2.x} ${p2.y}`;
  }
  return d;
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

export default function OITracker() {
  const INSTRUMENTS = ['NIFTY', 'BANKNIFTY', 'CRUDEOIL', 'SENSEX', 'FINNIFTY'];
  const [instrument, setInstrument] = useState('NIFTY');
  const [strikes, setStrikes] = useState([]);
  const [selectedStrike, setSelectedStrike] = useState('');
  const [timeSeries, setTimeSeries] = useState([]);
  const [lastUpdated, setLastUpdated] = useState(null);

  const [plotRef, plotSize] = useElementSize();
  const [hoverIndex, setHoverIndex] = useState(null);
  const [pxPerPoint, setPxPerPoint] = useState(DEFAULT_PX);
  const [isLive, setIsLive] = useState(true);
  const [dragging, setDragging] = useState(false);

  const scrollRef = useRef(null);
  const isLiveRef = useRef(true);
  const dragRef = useRef({ active: false, startX: 0, startScroll: 0 });
  const pointerTypeRef = useRef('mouse');
  const zoomAnchorRef = useRef(null);

  // ---------- Data fetching ----------
  useEffect(() => {
    let isMounted = true;
    (async () => {
      try {
        const res = await api.get(`/api/oi/strikes/${instrument}`);
        if (!isMounted) return;
        const list = Array.isArray(res.data) ? res.data : [];
        setStrikes(list);
        setSelectedStrike(list.length > 0 ? list[Math.floor(list.length / 2)] : '');
      } catch (error) {
        console.error('Failed to fetch strikes:', error);
      }
    })();
    return () => { isMounted = false; };
  }, [instrument]);

  useEffect(() => {
    if (!selectedStrike) return;
    let isMounted = true;

    // New instrument/strike -> start in live mode
    isLiveRef.current = true;
    setIsLive(true);
    setTimeSeries([]);

    const fetchStrikeData = async () => {
      try {
        const res = await api.get(`/api/oi/strike/${instrument}/${selectedStrike}?_t=${Date.now()}`);
        if (!isMounted) return;
        setTimeSeries(Array.isArray(res.data) ? res.data : []);
        setLastUpdated(new Date());
      } catch (error) {
        console.error(`Failed to fetch data for ${selectedStrike}:`, error);
      }
    };

    fetchStrikeData();
    const id = setInterval(fetchStrikeData, 30000);
    return () => { isMounted = false; clearInterval(id); };
  }, [instrument, selectedStrike]);

  const chartData = useMemo(() => {
    const map = new Map();
    timeSeries.forEach((row) => map.set(row.timestamp, row));
    return Array.from(map.values()).sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
  }, [timeSeries]);

  // ---------- Scroll helpers ----------
  const scrollToEnd = useCallback((smooth = false) => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTo({ left: el.scrollWidth, behavior: smooth ? 'smooth' : 'auto' });
  }, []);

  // Follow new data ONLY when the user is at the live edge
  useLayoutEffect(() => {
    if (isLiveRef.current) scrollToEnd();
  }, [chartData.length, plotSize.width, scrollToEnd]);

  // Keep the visual center stable when zooming
  useLayoutEffect(() => {
    const el = scrollRef.current;
    const anchor = zoomAnchorRef.current;
    if (!el || anchor == null) return;
    zoomAnchorRef.current = null;
    if (isLiveRef.current) scrollToEnd();
    else el.scrollLeft = anchor * el.scrollWidth - el.clientWidth / 2;
  }, [pxPerPoint, scrollToEnd]);

  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    const atEnd = el.scrollWidth - el.scrollLeft - el.clientWidth < 24;
    isLiveRef.current = atEnd;
    setIsLive((prev) => (prev === atEnd ? prev : atEnd));
    if (pointerTypeRef.current !== 'mouse') setHoverIndex(null);
  };

  const zoom = (factor) => {
    const el = scrollRef.current;
    if (el) zoomAnchorRef.current = (el.scrollLeft + el.clientWidth / 2) / el.scrollWidth;
    setPxPerPoint((p) => clamp(p * factor, MIN_PX, MAX_PX));
  };

  const panBy = (dir) => {
    const el = scrollRef.current;
    if (el) el.scrollBy({ left: dir * el.clientWidth * 0.6, behavior: 'smooth' });
  };

  const onWheel = (e) => {
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      zoom(e.deltaY < 0 ? 1.15 : 1 / 1.15);
    } else if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
      // vertical wheel pans horizontally
      scrollRef.current.scrollLeft += e.deltaY;
    }
  };

  // Non-passive wheel listener so preventDefault works for ctrl+wheel zoom
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  });

  // ---------- Formatters ----------
  const formatTime = (ts) => (ts ? ts.split('T')[1]?.substring(0, 5) : '');
  const formatDateShort = (ts) => (ts ? new Date(ts).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : '');
  const formatCompact = (val) => Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 }).format(val);
  const formatAgo = (date) => {
    if (!date) return '';
    const secs = Math.max(0, Math.round((Date.now() - date.getTime()) / 1000));
    if (secs < 5) return 'just now';
    if (secs < 60) return `${secs}s ago`;
    return `${Math.floor(secs / 60)}m ago`;
  };

  // ---------- Chart ----------
  const renderChart = () => {
    if (chartData.length < 2) {
      return (
        <div className="oi-empty-state">
          <span className="oi-empty-pulse" />
          Waiting for live data…
        </div>
      );
    }

    const viewportW = Math.max(200, plotSize.width - AXIS_W);
    const height = Math.max(160, plotSize.height - SCROLLBAR_H);
    const paddingX = 16;       // left gap inside scroll area (axis is separate)
    const paddingRight = 84;   // room for end labels
    const paddingY = 36;

    const width = Math.max(viewportW, chartData.length * pxPerPoint + paddingX + paddingRight);
    const plotWidth = width - paddingX - paddingRight;
    const plotHeight = height - paddingY * 2;
    const n = chartData.length;

    const allValues = chartData.flatMap((d) => [d.ceOi, d.peOi]);
    const rawMax = Math.max(...allValues);
    const rawMin = Math.min(...allValues);
    const pad = (rawMax - rawMin) * 0.18 || rawMax * 0.1 || 1000;
    const maxVal = rawMax + pad;
    const minVal = Math.max(0, rawMin - pad);
    const range = maxVal - minVal || 1;

    const getX = (i) => paddingX + (i / (n - 1)) * plotWidth;
    const getY = (v) => height - paddingY - ((v - minVal) / range) * plotHeight;
    const floorY = height - paddingY;

    const cePoints = chartData.map((d, i) => ({ x: getX(i), y: getY(d.ceOi) }));
    const pePoints = chartData.map((d, i) => ({ x: getX(i), y: getY(d.peOi) }));
    const ceLine = smoothPath(cePoints);
    const peLine = smoothPath(pePoints);
    const ceArea = `${ceLine} L ${cePoints[n - 1].x} ${floorY} L ${cePoints[0].x} ${floorY} Z`;
    const peArea = `${peLine} L ${pePoints[n - 1].x} ${floorY} L ${pePoints[0].x} ${floorY} Z`;

    const gridCount = 4;
    const gridLines = Array.from({ length: gridCount }, (_, i) => {
      const t = i / (gridCount - 1);
      return { y: paddingY + t * plotHeight, val: maxVal - t * (maxVal - minVal) };
    });

    // Day regions
    const dayRegions = [];
    let currentDay = chartData[0].timestamp.split('T')[0];
    let startIdx = 0;
    chartData.forEach((d, i) => {
      const day = d.timestamp.split('T')[0];
      if (day !== currentDay) {
        dayRegions.push({ startIdx, endIdx: i, date: currentDay });
        currentDay = day;
        startIdx = i;
      }
    });
    dayRegions.push({ startIdx, endIdx: n - 1, date: currentDay });

    // Time ticks: at least ~90px apart
    const tickStep = Math.max(1, Math.ceil(90 / pxPerPoint));
    const ticks = [];
    for (let i = 0; i < n; i += tickStep) {
      if (getX(n - 1) - getX(i) < 40 && i !== 0) continue;
      ticks.push(i);
    }

    // Hover
    const updateHover = (clientX) => {
      const el = scrollRef.current;
      const rect = el.getBoundingClientRect();
      const x = clientX - rect.left + el.scrollLeft - paddingX;
      const idx = Math.round((x / plotWidth) * (n - 1));
      setHoverIndex(clamp(idx, 0, n - 1));
    };

    const onPointerDown = (e) => {
      pointerTypeRef.current = e.pointerType;
      if (e.pointerType === 'mouse') {
        if (e.button !== 0) return;
        dragRef.current = { active: true, startX: e.clientX, startScroll: scrollRef.current.scrollLeft };
        scrollRef.current.setPointerCapture(e.pointerId);
        setDragging(true);
        setHoverIndex(null);
      } else {
        updateHover(e.clientX); // tap on touch shows tooltip; swipe pans natively
      }
    };

    const onPointerMove = (e) => {
      if (e.pointerType !== 'mouse') return;
      if (dragRef.current.active) {
        scrollRef.current.scrollLeft = dragRef.current.startScroll - (e.clientX - dragRef.current.startX);
      } else {
        updateHover(e.clientX);
      }
    };

    const endDrag = (e) => {
      if (!dragRef.current.active) return;
      dragRef.current.active = false;
      setDragging(false);
      try { scrollRef.current.releasePointerCapture(e.pointerId); } catch (_) {}
    };

    const hovered = hoverIndex !== null ? chartData[hoverIndex] : null;
    const tooltipW = 176;
    let tooltipLeft = hovered ? getX(hoverIndex) + 16 : 0;
    if (hovered && tooltipLeft + tooltipW > width) tooltipLeft = getX(hoverIndex) - tooltipW - 16;

    const lastCe = chartData[n - 1].ceOi;
    const lastPe = chartData[n - 1].peOi;

    return (
      <>
        {/* Fixed Y-axis: never scrolls out of view */}
        <svg className="oi-y-axis" width={AXIS_W} height={height}>
          {gridLines.map((g, i) => (
            <text key={i} x={AXIS_W - 10} y={g.y + 4} className="oi-axis-label" textAnchor="end">
              {formatCompact(g.val)}
            </text>
          ))}
        </svg>

        <div
          className={`oi-chart-scroll-container${dragging ? ' is-dragging' : ''}`}
          ref={scrollRef}
          onScroll={onScroll}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          onPointerLeave={(e) => { if (e.pointerType === 'mouse' && !dragRef.current.active) setHoverIndex(null); }}
        >
          <div className="oi-chart-svg-wrapper" style={{ width, height }}>
            <svg width={width} height={height} className="oi-chart-svg">
              <defs>
                <linearGradient id="oiFillCe" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#f87171" stopOpacity="0.22" />
                  <stop offset="100%" stopColor="#f87171" stopOpacity="0" />
                </linearGradient>
                <linearGradient id="oiFillPe" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#34d399" stopOpacity="0.22" />
                  <stop offset="100%" stopColor="#34d399" stopOpacity="0" />
                </linearGradient>
              </defs>

              {/* Alternating day shading */}
              {dayRegions.map((r, j) =>
                j % 2 === 0 ? null : (
                  <rect key={`shade-${j}`} x={getX(r.startIdx)} y={paddingY}
                    width={getX(r.endIdx) - getX(r.startIdx)} height={plotHeight}
                    fill="var(--text-faint)" fillOpacity="0.04" />
                )
              )}

              {/* Day boundaries */}
              {dayRegions.map((r, j) => {
                if (j === 0) return null;
                const x = getX(r.startIdx);
                return (
                  <g key={`boundary-${j}`}>
                    <line x1={x} y1={paddingY} x2={x} y2={floorY} stroke="var(--glass-border)"
                      strokeWidth="1.5" strokeDasharray="4 4" opacity="0.8" />
                    <text x={x + 6} y={paddingY + 12} className="oi-axis-label" style={{ fontWeight: 'bold' }}>
                      {formatDateShort(chartData[r.startIdx].timestamp)}
                    </text>
                  </g>
                );
              })}

              {/* Grid */}
              {gridLines.map((g, i) => (
                <line key={i} x1={paddingX} y1={g.y} x2={width - paddingRight} y2={g.y} className="oi-grid-line" />
              ))}

              {/* X ticks */}
              {ticks.map((i) => (
                <g key={`tick-${i}`}>
                  <line x1={getX(i)} y1={floorY} x2={getX(i)} y2={floorY + 5} className="oi-tick" />
                  <text x={getX(i)} y={floorY + 20} className="oi-axis-label" textAnchor="middle">
                    {formatTime(chartData[i].timestamp)}
                  </text>
                </g>
              ))}

              <path d={ceArea} className="oi-area-ce" />
              <path d={peArea} className="oi-area-pe" />
              <path d={ceLine} className="oi-line-ce" />
              <path d={peLine} className="oi-line-pe" />

              <circle cx={cePoints[n - 1].x} cy={cePoints[n - 1].y} r="4" className="oi-dot-ce oi-dot-end" />
              <circle cx={pePoints[n - 1].x} cy={pePoints[n - 1].y} r="4" className="oi-dot-pe oi-dot-end" />

              <text x={width - paddingRight + 10} y={cePoints[n - 1].y + 4} className="oi-end-label oi-end-label-ce">
                {formatCompact(lastCe)}
              </text>
              <text x={width - paddingRight + 10} y={pePoints[n - 1].y + 4} className="oi-end-label oi-end-label-pe">
                {formatCompact(lastPe)}
              </text>

              {hovered && (
                <>
                  <line x1={getX(hoverIndex)} y1={paddingY} x2={getX(hoverIndex)} y2={floorY} className="oi-crosshair" />
                  <circle cx={getX(hoverIndex)} cy={getY(hovered.ceOi)} r="5" className="oi-dot-ce" />
                  <circle cx={getX(hoverIndex)} cy={getY(hovered.peOi)} r="5" className="oi-dot-pe" />
                </>
              )}
            </svg>

            {hovered && (
              <div className="oi-chart-tooltip" style={{ left: tooltipLeft, top: 36 }}>
                <div className="oi-tooltip-time">
                  {formatDateShort(hovered.timestamp)} • {formatTime(hovered.timestamp)}
                </div>
                <div className="oi-tooltip-row"><span><span className="oi-dot ce" /> CE OI</span><b>{hovered.ceOi.toLocaleString('en-IN')}</b></div>
                <div className="oi-tooltip-row"><span><span className="oi-dot pe" /> PE OI</span><b>{hovered.peOi.toLocaleString('en-IN')}</b></div>
              </div>
            )}
          </div>
        </div>
      </>
    );
  };

  return (
    <div className="oi-tracker-container">
      <div className="oi-toolbar">
        <div className="oi-header-left">
          <h2 className="oi-title">
            <span className="oi-live-dot" aria-hidden="true" />
            OI Momentum
          </h2>

          <select value={instrument} onChange={(e) => setInstrument(e.target.value)} className="oi-dropdown">
            {INSTRUMENTS.map((inst) => <option key={inst} value={inst}>{inst}</option>)}
          </select>

          <select value={selectedStrike} onChange={(e) => setSelectedStrike(Number(e.target.value))} className="oi-dropdown">
            {strikes.map((stk) => <option key={stk} value={stk}>{stk}</option>)}
          </select>

          {lastUpdated && <span className="oi-updated">Updated {formatAgo(lastUpdated)}</span>}
        </div>

        <div className="oi-toolbar-right">
          <div className="oi-controls">
            <button className="oi-btn" onClick={() => panBy(-1)} title="Pan back" aria-label="Pan back">◀</button>
            <button className="oi-btn" onClick={() => panBy(1)} title="Pan forward" aria-label="Pan forward">▶</button>
            <button className="oi-btn" onClick={() => zoom(1 / 1.3)} title="Zoom out" aria-label="Zoom out">−</button>
            <button className="oi-btn" onClick={() => zoom(1.3)} title="Zoom in" aria-label="Zoom in">+</button>
            <button
              className={`oi-btn oi-btn-live${isLive ? ' active' : ''}`}
              onClick={() => { isLiveRef.current = true; scrollToEnd(true); }}
              title="Jump to latest"
            >
              ● Live
            </button>
          </div>
          <div className="oi-legend">
            <div className="oi-legend-item"><span className="oi-dot ce" /> CE OI · Resistance</div>
            <div className="oi-legend-item"><span className="oi-dot pe" /> PE OI · Support</div>
          </div>
        </div>
      </div>

      <div className="oi-chart-panel">
        <div className="oi-chart-surface" ref={plotRef}>
          {renderChart()}
        </div>
      </div>
    </div>
  );
}