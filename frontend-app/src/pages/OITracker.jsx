import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import api from '../services/api';
import './OITracker.css';

// Custom hook to measure the chart container size so the SVG scales perfectly.
// Uses a callback ref (not a plain ref + one-time effect) so the observer
// re-attaches whenever the underlying DOM node changes — including the case
// where the measured element wasn't mounted yet on first render.
function useElementSize() {
  const [node, setNode] = useState(null);
  const [size, setSize] = useState({ width: 800, height: 400 });

  const setRef = useCallback((el) => {
    setNode(el);
  }, []);

  useEffect(() => {
    if (!node) return;
    const ro = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      if (width > 0 && height > 0) setSize({ width, height });
    });
    ro.observe(node);
    // Capture the size immediately too, in case layout is already settled
    const rect = node.getBoundingClientRect();
    if (rect.width > 0 && rect.height > 0) setSize({ width: rect.width, height: rect.height });
    return () => ro.disconnect();
  }, [node]);

  return [setRef, size];
}

// Catmull-Rom -> cubic Bezier conversion for a genuinely smooth, natural curve
// (replaces the old per-point quadratic "T" patch, which kinked at every vertex)
function smoothPath(points) {
  if (points.length < 2) return '';
  if (points.length === 2) {
    return `M ${points[0].x} ${points[0].y} L ${points[1].x} ${points[1].y}`;
  }
  let d = `M ${points[0].x} ${points[0].y}`;
  for (let i = 0; i < points.length - 1; i++) {
    const p0 = points[i - 1] || points[i];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = points[i + 2] || p2;
    const c1x = p1.x + (p2.x - p0.x) / 6;
    const c1y = p1.y + (p2.y - p0.y) / 6;
    const c2x = p2.x - (p3.x - p1.x) / 6;
    const c2y = p2.y - (p3.y - p1.y) / 6;
    d += ` C ${c1x} ${c1y}, ${c2x} ${c2y}, ${p2.x} ${p2.y}`;
  }
  return d;
}

export default function OITracker() {
  const INSTRUMENTS = ['NIFTY', 'BANKNIFTY', 'CRUDEOIL', 'SENSEX', 'FINNIFTY'];
  const [instrument, setInstrument] = useState('NIFTY');
  const [strikes, setStrikes] = useState([]);
  const [selectedStrike, setSelectedStrike] = useState('');
  const [timeSeries, setTimeSeries] = useState([]);
  const [lastUpdated, setLastUpdated] = useState(null);

  const [plotRef, plotSize] = useElementSize();
  const [hoverIndex, setHoverIndex] = useState(null);

  useEffect(() => {
    let isMounted = true;
    const fetchStrikes = async () => {
      try {
        const res = await api.get(`/api/oi/strikes/${instrument}`);
        if (!isMounted) return;

        const strikeList = Array.isArray(res.data) ? res.data : [];
        setStrikes(strikeList);

        if (strikeList.length > 0) {
          setSelectedStrike(strikeList[Math.floor(strikeList.length / 2)]);
        } else {
          setSelectedStrike('');
        }
      } catch (error) {
        console.error("Failed to fetch strikes:", error);
      }
    };
    fetchStrikes();
    return () => { isMounted = false; };
  }, [instrument]);

  useEffect(() => {
    if (!selectedStrike) return;
    let isMounted = true;

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
    const intervalId = setInterval(fetchStrikeData, 30000);

    return () => {
      isMounted = false;
      clearInterval(intervalId);
    };
  }, [instrument, selectedStrike]);

  const chartData = useMemo(() => {
    const map = new Map();
    timeSeries.forEach(row => map.set(row.timestamp, row));
    return Array.from(map.values()).sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
  }, [timeSeries]);

  const handleHover = useCallback((clientX, currentTarget, paddingX, plotWidth) => {
    const rect = currentTarget.getBoundingClientRect();
    const x = Math.max(0, clientX - rect.left - paddingX);
    const ratio = Math.min(1, x / plotWidth);
    const idx = Math.round(ratio * (chartData.length - 1));
    setHoverIndex(Math.max(0, Math.min(chartData.length - 1, idx)));
  }, [chartData.length]);

  const clearHover = () => setHoverIndex(null);

  const formatTime = (ts) => ts ? ts.split('T')[1]?.substring(0, 5) : '';
  const formatCompact = (val) => Intl.NumberFormat('en-IN', { notation: "compact", maximumFractionDigits: 1 }).format(val);
  const formatAgo = (date) => {
    if (!date) return '';
    const secs = Math.max(0, Math.round((Date.now() - date.getTime()) / 1000));
    if (secs < 5) return 'just now';
    if (secs < 60) return `${secs}s ago`;
    return `${Math.floor(secs / 60)}m ago`;
  };

  const renderChart = () => {
    if (chartData.length < 2) {
      return (
        <div className="oi-empty-state">
          <span className="oi-empty-pulse" />
          Waiting for live data…
        </div>
      );
    }

    const { width, height } = plotSize;
    const paddingX = 68;
    const paddingRight = 84;
    const paddingY = 36;
    const plotWidth = width - paddingX - paddingRight;
    const plotHeight = height - paddingY * 2;

    const allValues = chartData.flatMap(d => [d.ceOi, d.peOi]);
    const rawMax = Math.max(...allValues);
    const rawMin = Math.min(...allValues);
    // Pad the domain around the actual data range (don't force a 0 floor —
    // OI values rarely sit near zero, and flooring there squashed all the
    // real movement into the top slice of the chart, leaving the bottom empty).
    const pad = (rawMax - rawMin) * 0.18 || rawMax * 0.1 || 1000;
    const maxVal = rawMax + pad;
    const minVal = Math.max(0, rawMin - pad);
    const range = (maxVal - minVal) || 1;

    const getX = (i) => paddingX + (i / (chartData.length - 1)) * plotWidth;
    const getY = (v) => height - paddingY - ((v - minVal) / range) * plotHeight;

    const cePoints = chartData.map((d, i) => ({ x: getX(i), y: getY(d.ceOi) }));
    const pePoints = chartData.map((d, i) => ({ x: getX(i), y: getY(d.peOi) }));
    const ceLine = smoothPath(cePoints);
    const peLine = smoothPath(pePoints);
    const floorY = height - paddingY;
    const ceArea = `${ceLine} L ${cePoints[cePoints.length - 1].x} ${floorY} L ${cePoints[0].x} ${floorY} Z`;
    const peArea = `${peLine} L ${pePoints[pePoints.length - 1].x} ${floorY} L ${pePoints[0].x} ${floorY} Z`;

    const gridCount = 3;
    const gridLines = Array.from({ length: gridCount }, (_, i) => {
      const t = i / (gridCount - 1);
      return { y: paddingY + t * plotHeight, val: maxVal - t * (maxVal - minVal) };
    });

    const hovered = hoverIndex !== null ? chartData[hoverIndex] : null;
    const tooltipW = 176;
    let tooltipLeft = hovered ? getX(hoverIndex) + 16 : 0;
    if (hovered && tooltipLeft + tooltipW > width) tooltipLeft = getX(hoverIndex) - tooltipW - 16;

    const lastCe = chartData[chartData.length - 1].ceOi;
    const lastPe = chartData[chartData.length - 1].peOi;

    return (
      <div
        className="oi-chart-svg-wrapper"
        onMouseMove={(e) => handleHover(e.clientX, e.currentTarget, paddingX, plotWidth)}
        onMouseLeave={clearHover}
        onTouchMove={(e) => e.touches[0] && handleHover(e.touches[0].clientX, e.currentTarget, paddingX, plotWidth)}
        onTouchEnd={clearHover}
      >
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
            <linearGradient id="oiFadeLeft" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0%" stopColor="var(--panel-bg, #0b0f14)" stopOpacity="0.9" />
              <stop offset="100%" stopColor="var(--panel-bg, #0b0f14)" stopOpacity="0" />
            </linearGradient>
          </defs>

          {/* Grid */}
          {gridLines.map((g, i) => (
            <line key={i} x1={paddingX} y1={g.y} x2={width - paddingRight} y2={g.y} className="oi-grid-line" />
          ))}

          {/* Y-Axis Labels */}
          {gridLines.map((g, i) => (
            <text key={i} x={paddingX - 12} y={g.y + 4} className="oi-axis-label" textAnchor="end">
              {formatCompact(g.val)}
            </text>
          ))}

          {/* X-Axis Labels */}
          <text x={getX(0)} y={height - paddingY + 22} className="oi-axis-label" textAnchor="start">{formatTime(chartData[0]?.timestamp)}</text>
          <text x={getX(chartData.length - 1)} y={height - paddingY + 22} className="oi-axis-label" textAnchor="end">{formatTime(chartData[chartData.length - 1]?.timestamp)}</text>

          {/* Area fills */}
          <path d={ceArea} className="oi-area-ce" />
          <path d={peArea} className="oi-area-pe" />

          {/* Smooth trend lines */}
          <path d={ceLine} className="oi-line-ce" />
          <path d={peLine} className="oi-line-pe" />

          {/* End-of-line markers */}
          <circle cx={cePoints[cePoints.length - 1].x} cy={cePoints[cePoints.length - 1].y} r="4" className="oi-dot-ce oi-dot-end" />
          <circle cx={pePoints[pePoints.length - 1].x} cy={pePoints[pePoints.length - 1].y} r="4" className="oi-dot-pe oi-dot-end" />

          {/* Value badges at line ends */}
          <text x={width - paddingRight + 10} y={cePoints[cePoints.length - 1].y + 4} className="oi-end-label oi-end-label-ce">
            {formatCompact(lastCe)}
          </text>
          <text x={width - paddingRight + 10} y={pePoints[pePoints.length - 1].y + 4} className="oi-end-label oi-end-label-pe">
            {formatCompact(lastPe)}
          </text>

          {/* Hover overlays */}
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
            <div className="oi-tooltip-time">{formatTime(hovered.timestamp)}</div>
            <div className="oi-tooltip-row"><span><span className="oi-dot ce" /> CE OI</span><b>{hovered.ceOi.toLocaleString('en-IN')}</b></div>
            <div className="oi-tooltip-row"><span><span className="oi-dot pe" /> PE OI</span><b>{hovered.peOi.toLocaleString('en-IN')}</b></div>
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="oi-tracker-container">

      {/* Glassy Toolbar */}
      <div className="oi-toolbar">
        <div className="oi-header-left">
          <h2 className="oi-title">
            <span className="oi-live-dot" aria-hidden="true" />
            OI Momentum
          </h2>

          <select value={instrument} onChange={(e) => setInstrument(e.target.value)} className="oi-dropdown">
            {INSTRUMENTS.map(inst => <option key={inst} value={inst}>{inst}</option>)}
          </select>

          <select value={selectedStrike} onChange={(e) => setSelectedStrike(Number(e.target.value))} className="oi-dropdown">
            {strikes.map(stk => <option key={stk} value={stk}>{stk}</option>)}
          </select>

          {lastUpdated && <span className="oi-updated">Updated {formatAgo(lastUpdated)}</span>}
        </div>

        <div className="oi-legend">
          <div className="oi-legend-item"><span className="oi-dot ce"/> CE OI · Resistance</div>
          <div className="oi-legend-item"><span className="oi-dot pe"/> PE OI · Support</div>
        </div>
      </div>

      {/* Glassy Chart Panel */}
      <div className="oi-chart-panel">
        <div className="oi-chart-surface" ref={plotRef}>
          {renderChart()}
        </div>
      </div>
    </div>
  );
}