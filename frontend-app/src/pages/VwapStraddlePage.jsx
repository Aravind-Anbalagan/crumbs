import React, { useCallback, useEffect, useRef, useState } from 'react';
import * as LC from 'lightweight-charts';
import './VwapStraddlePage.css';

const BASE_URL = `${import.meta.env.VITE_API_BASE_URL || ''}/api/vwap-straddle`;
const IST_OFFSET_SEC = 5.5 * 60 * 60;

async function api(path, options = {}) {
    const res = await fetch(`${BASE_URL}${path}`, {
        headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
        ...options,
    });
    if (!res.ok) {
        const text = await res.text();
        let msg = `Request failed (${res.status})`;
        try {
            const json = JSON.parse(text);
            msg = json.message || json.error || msg;
        } catch {
            if (text) msg = text;
        }
        throw new Error(msg);
    }
    return res.status === 204 ? null : res.json();
}

function getIsLightTheme(el) {
    const canvas =
        el?.closest?.('.layout-canvas') || document.querySelector('.layout-canvas');
    return canvas ? canvas.classList.contains('theme-light') : false;
}

function getChartThemeOptions(isLight) {
    return {
        layout: {
            background: { color: isLight ? '#ffffff' : '#0b1220' },
            textColor: isLight ? '#334155' : '#94a3b8',
            fontFamily: 'Inter, system-ui, -apple-system, sans-serif',
        },
        grid: {
            vertLines: {
                color: isLight ? 'rgba(15, 23, 42, 0.07)' : 'rgba(148, 163, 184, 0.08)',
            },
            horzLines: {
                color: isLight ? 'rgba(15, 23, 42, 0.07)' : 'rgba(148, 163, 184, 0.08)',
            },
        },
        rightPriceScale: {
            borderColor: isLight ? '#e2e8f0' : '#1e293b',
            scaleMargins: { top: 0.12, bottom: 0.12 },
        },
        timeScale: {
            borderColor: isLight ? '#e2e8f0' : '#1e293b',
            timeVisible: true,
            secondsVisible: false,
        },
        crosshair: {
            mode: LC.CrosshairMode.Normal,
            vertLine: {
                color: isLight ? '#94a3b8' : '#475569',
                style: LC.LineStyle.Dashed,
            },
            horzLine: {
                color: isLight ? '#94a3b8' : '#475569',
                style: LC.LineStyle.Dashed,
            },
        },
    };
}

// ── Two-Line Chart Panel (Mounts only when a row is selected) ────────────────
function StraddleChartPanel({ item, ticks, onClose }) {
    const containerRef = useRef(null);
    const chartRef = useRef(null);
    const cpSeriesRef = useRef(null);
    const cvSeriesRef = useRef(null);
    const markersPluginRef = useRef(null);

    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;

        const initialLight = getIsLightTheme(el);

        const chart = LC.createChart(el, {
            height: 480,
            width: el.clientWidth,
            ...getChartThemeOptions(initialLight),
        });

        const addLine = (opts) =>
            LC.LineSeries ? chart.addSeries(LC.LineSeries, opts) : chart.addLineSeries(opts);

        cpSeriesRef.current = addLine({
            color: initialLight ? '#0284c7' : '#38bdf8',
            lineWidth: 2.5,
            title: 'CP',
            priceLineVisible: true,
        });

        cvSeriesRef.current = addLine({
            color: initialLight ? '#d97706' : '#f59e0b',
            lineWidth: 2,
            lineStyle: LC.LineStyle.Dashed,
            title: 'CV',
            priceLineVisible: true,
        });

        chartRef.current = chart;

        const resizeObserver = new ResizeObserver((entries) => {
            if (entries[0]?.contentRect?.width) {
                chart.applyOptions({ width: entries[0].contentRect.width });
            }
        });
        resizeObserver.observe(el);

        // Sync chart colors live when MainLayout toggles theme-dark / theme-light
        const layoutEl =
            el.closest('.layout-canvas') || document.querySelector('.layout-canvas');
        let themeObserver;
        if (layoutEl) {
            themeObserver = new MutationObserver(() => {
                const isLight = layoutEl.classList.contains('theme-light');
                chart.applyOptions(getChartThemeOptions(isLight));
                cpSeriesRef.current?.applyOptions({
                    color: isLight ? '#0284c7' : '#38bdf8',
                });
                cvSeriesRef.current?.applyOptions({
                    color: isLight ? '#d97706' : '#f59e0b',
                });
            });
            themeObserver.observe(layoutEl, {
                attributes: true,
                attributeFilter: ['class'],
            });
        }

        return () => {
            resizeObserver.disconnect();
            if (themeObserver) themeObserver.disconnect();
            markersPluginRef.current = null;
            chart.remove();
        };
    }, []);

    useEffect(() => {
        if (!cpSeriesRef.current || !cvSeriesRef.current) return;

        const byTime = new Map();
        for (const t of ticks || []) {
            if (t.epochSeconds != null) byTime.set(t.epochSeconds, t);
        }
        const sorted = Array.from(byTime.values()).sort(
            (a, b) => a.epochSeconds - b.epochSeconds
        );

        const cpData = [];
        const cvData = [];
        const markers = [];

        for (const t of sorted) {
            const time = t.epochSeconds + IST_OFFSET_SEC;
            cpData.push({ time, value: Number(t.combinedPrice) });
            cvData.push({ time, value: Number(t.combinedVwap) });

            if (t.signalEvent === 'ENTRY') {
                markers.push({
                    time,
                    position: 'belowBar',
                    color: '#16a34a',
                    shape: 'arrowUp',
                    text: 'ENTRY',
                });
            } else if (t.signalEvent === 'EXIT') {
                markers.push({
                    time,
                    position: 'aboveBar',
                    color: '#dc2626',
                    shape: 'arrowDown',
                    text: 'EXIT',
                });
            }
        }

        cpSeriesRef.current.setData(cpData);
        cvSeriesRef.current.setData(cvData);

        if (typeof LC.createSeriesMarkers === 'function') {
            if (!markersPluginRef.current) {
                markersPluginRef.current = LC.createSeriesMarkers(cpSeriesRef.current, markers);
            } else {
                markersPluginRef.current.setMarkers(markers);
            }
        } else if (typeof cpSeriesRef.current.setMarkers === 'function') {
            cpSeriesRef.current.setMarkers(markers);
        }

        if (sorted.length > 0) {
            chartRef.current.timeScale().fitContent();
        }
    }, [ticks]);

    const diff =
        item.liveCp != null && item.liveCv != null
            ? Number(item.liveCp) - Number(item.liveCv)
            : null;

    return (
        <section className="vs-chart-panel">
            <div className="vs-chart-header">
                <div className="vs-chart-title-group">
                    <div className="vs-chart-symbol-row">
                        <h3>
                            {item.symbol} <span className="vs-strike-accent">{Number(item.strike)}</span>
                        </h3>
                        <span className="vs-expiry-chip">{item.expiry}</span>
                        <span className={`vs-status-pill ${item.status.toLowerCase()}`}>
              <span className="vs-dot" />
                            {item.status === 'IN_POSITION' ? 'IN POSITION' : 'WAITING'}
            </span>
                    </div>

                    <div className="vs-chart-metrics">
                        <div className="vs-metric">
                            <span className="vs-metric-label">Combined Price</span>
                            <span className="vs-metric-val cp">
                {item.liveCp != null ? Number(item.liveCp).toFixed(2) : '—'}
              </span>
                        </div>
                        <div className="vs-metric">
                            <span className="vs-metric-label">Combined VWAP</span>
                            <span className="vs-metric-val cv">
                {item.liveCv != null ? Number(item.liveCv).toFixed(2) : '—'}
              </span>
                        </div>
                        {diff != null && (
                            <div className="vs-metric">
                                <span className="vs-metric-label">CP − CV</span>
                                <span className={`vs-metric-val ${diff < 0 ? 'pos' : 'neg'}`}>
                  {diff > 0 ? `+${diff.toFixed(2)}` : diff.toFixed(2)}
                </span>
                            </div>
                        )}
                        {item.entryCp != null && (
                            <div className="vs-metric">
                                <span className="vs-metric-label">Entry CP</span>
                                <span className="vs-metric-val">{Number(item.entryCp).toFixed(2)}</span>
                            </div>
                        )}
                    </div>
                </div>

                <div className="vs-chart-controls">
                    <div className="vs-legend">
            <span className="vs-legend-item cp">
              <i className="vs-line-swatch solid" /> CP
            </span>
                        <span className="vs-legend-item cv">
              <i className="vs-line-swatch dashed" /> CV
            </span>
                    </div>
                    <button className="vs-icon-btn close" onClick={onClose} title="Close chart">
                        ✕
                    </button>
                </div>
            </div>

            <div className="vs-chart-body">
                <div ref={containerRef} className="vs-chart-canvas" />
                {(!ticks || ticks.length === 0) && (
                    <div className="vs-chart-empty-overlay">
                        <span className="vs-pulse-ring" />
                        <span>Waiting for the next 1-minute tick (09:16 – 15:29 IST)</span>
                    </div>
                )}
            </div>
        </section>
    );
}

// ── Main Single-Page Dashboard ───────────────────────────────────────────────
export default function VwapStraddlePage() {
    const [symbols, setSymbols] = useState([]);
    const [strikes, setStrikes] = useState([]);

    const [symbol, setSymbol] = useState('');
    const [strike, setStrike] = useState('');
    const [entryHitTarget, setEntryHitTarget] = useState(5);
    const [exitHitTarget, setExitHitTarget] = useState(5);
    const [alertFlag, setAlertFlag] = useState('Y');
    const [submitting, setSubmitting] = useState(false);

    const [watchlist, setWatchlist] = useState([]);
    const [selectedId, setSelectedId] = useState(null);
    const [chartTicks, setChartTicks] = useState([]);
    const [error, setError] = useState('');
    const [lastSync, setLastSync] = useState('');
    const [refreshing, setRefreshing] = useState(false);
    const [patching, setPatching] = useState({});

    const loadWatchlist = useCallback(async () => {
        try {
            const data = await api('/watchlist');
            setWatchlist(data);
            setLastSync(new Date().toLocaleTimeString('en-IN', { hour12: false }));
            setSelectedId((prev) => (prev && data.some((d) => d.id === prev) ? prev : null));
        } catch (err) {
            setError(err.message);
        }
    }, []);

    const loadChart = useCallback(async (id) => {
        if (!id) {
            setChartTicks([]);
            return;
        }
        try {
            setChartTicks(await api(`/watchlist/${id}/chart`));
        } catch (err) {
            setError(err.message);
        }
    }, []);

    const handleManualRefresh = async () => {
        setRefreshing(true);
        await loadWatchlist();
        if (selectedId) await loadChart(selectedId);
        setRefreshing(false);
    };

    useEffect(() => {
        api('/symbols').then(setSymbols).catch((err) => setError(err.message));
        loadWatchlist();
    }, [loadWatchlist]);

    useEffect(() => {
        const clean = symbol.trim().toUpperCase();
        if (!clean || !symbols.includes(clean)) {
            setStrikes([]);
            return;
        }
        let cancelled = false;
        api(`/strikes?symbol=${encodeURIComponent(clean)}`)
            .then((list) => !cancelled && setStrikes(list))
            .catch(() => !cancelled && setStrikes([]));
        return () => {
            cancelled = true;
        };
    }, [symbol, symbols]);

    useEffect(() => {
        loadChart(selectedId);
    }, [selectedId, loadChart]);

    // Sync automatically at :08s past every minute (right after :05s backend job)
    useEffect(() => {
        let intervalId;
        const sec = new Date().getSeconds();
        const delayMs = (sec < 8 ? 8 - sec : 68 - sec) * 1000;

        const tick = () => {
            loadWatchlist();
            if (selectedId) loadChart(selectedId);
        };

        const timeoutId = setTimeout(() => {
            tick();
            intervalId = setInterval(tick, 60_000);
        }, delayMs);

        return () => {
            clearTimeout(timeoutId);
            if (intervalId) clearInterval(intervalId);
        };
    }, [loadWatchlist, loadChart, selectedId]);

    const handleAdd = async (e) => {
        e.preventDefault();
        setError('');
        if (!symbol.trim() || !strike) {
            setError('Select both a stock and strike price.');
            return;
        }
        setSubmitting(true);
        try {
            await api('/watchlist', {
                method: 'POST',
                body: JSON.stringify({
                    symbol: symbol.trim().toUpperCase(),
                    strike: Number(strike),
                    entryHitTarget: Number(entryHitTarget) || 5,
                    exitHitTarget: Number(exitHitTarget) || 5,
                    alertFlag,
                }),
            });
            setStrike('');
            await loadWatchlist();
        } catch (err) {
            setError(err.message);
        } finally {
            setSubmitting(false);
        }
    };

    const handlePatch = async (id, patch, fieldKey) => {
        setError('');
        const key = fieldKey ? `${id}-${fieldKey}` : null;
        if (key) setPatching((prev) => ({ ...prev, [key]: true }));
        try {
            const updated = await api(`/watchlist/${id}`, {
                method: 'PATCH',
                body: JSON.stringify(patch),
            });
            setWatchlist((prev) => prev.map((r) => (r.id === id ? updated : r)));
        } catch (err) {
            setError(err.message);
        } finally {
            if (key) {
                setPatching((prev) => {
                    const next = { ...prev };
                    delete next[key];
                    return next;
                });
            }
        }
    };

    const handleHitBlur = (row, field, rawVal) => {
        const val = parseInt(rawVal, 10);
        if (!val || val < 1 || val === row[field]) return;
        handlePatch(row.id, { [field]: val });
    };

    const handleDelete = async (e, id) => {
        e.stopPropagation();
        setError('');
        try {
            await api(`/watchlist/${id}`, { method: 'DELETE' });
            setWatchlist((prev) => prev.filter((r) => r.id !== id));
            if (selectedId === id) {
                setSelectedId(null);
                setChartTicks([]);
            }
        } catch (err) {
            setError(err.message);
        }
    };

    const selectedItem = watchlist.find((w) => w.id === selectedId) || null;

    return (
        <div className="vs-shell">
            <div className="vs-container">
                {/* Add Strike Form */}
                <form className="vs-add-card" onSubmit={handleAdd}>
                    <div className="vs-field grow">
                        <label>Stock Symbol</label>
                        <input
                            list="vs-symbols"
                            placeholder="Search e.g. TCS, NIFTY, RELIANCE..."
                            value={symbol}
                            onChange={(e) => {
                                setSymbol(e.target.value.toUpperCase());
                                setStrike('');
                            }}
                            required
                        />
                        <datalist id="vs-symbols">
                            {symbols.map((s) => (
                                <option key={s} value={s} />
                            ))}
                        </datalist>
                    </div>

                    <div className="vs-field medium">
                        <label>Strike (Nearest Expiry)</label>
                        <input
                            list="vs-strikes"
                            type="number"
                            step="any"
                            placeholder="e.g. 3000"
                            value={strike}
                            onChange={(e) => setStrike(e.target.value)}
                            required
                        />
                        <datalist id="vs-strikes">
                            {strikes.map((st) => (
                                <option key={st} value={st} />
                            ))}
                        </datalist>
                    </div>

                    <div className="vs-field compact">
                        <label>Entry Hits</label>
                        <input
                            type="number"
                            min="1"
                            value={entryHitTarget}
                            onChange={(e) => setEntryHitTarget(e.target.value)}
                        />
                    </div>

                    <div className="vs-field compact">
                        <label>Exit Hits</label>
                        <input
                            type="number"
                            min="1"
                            value={exitHitTarget}
                            onChange={(e) => setExitHitTarget(e.target.value)}
                        />
                    </div>

                    <div className="vs-field compact">
                        <label>Alert</label>
                        <div className="vs-segmented">
                            <button
                                type="button"
                                className={alertFlag === 'Y' ? 'active' : ''}
                                onClick={() => setAlertFlag('Y')}
                            >
                                Y
                            </button>
                            <button
                                type="button"
                                className={alertFlag === 'N' ? 'active' : ''}
                                onClick={() => setAlertFlag('N')}
                            >
                                N
                            </button>
                        </div>
                    </div>

                    <button className="vs-btn-primary" type="submit" disabled={submitting}>
                        {submitting ? 'Adding...' : '+ Add to Watchlist'}
                    </button>
                </form>

                {/* Error Alert */}
                {error && (
                    <div className="vs-alert-error">
                        <span>⚠ {error}</span>
                        <button onClick={() => setError('')}>✕</button>
                    </div>
                )}

                {/* Watchlist Table */}
                <section className="vs-table-card">
                    <div className="vs-table-header">
                        <div className="vs-table-title-row">
                            <h2>Active Watchlist</h2>
                            <span className="vs-table-hint">
                {selectedItem
                    ? `Chart open: ${selectedItem.symbol} ${Number(selectedItem.strike)}`
                    : 'Select a row to inspect CP / CV chart'}
              </span>
                        </div>

                        <div className="vs-table-actions">
                            {lastSync && (
                                <span className="vs-sync-inline">
                  Synced <strong className="mono">{lastSync}</strong>
                </span>
                            )}
                            <button
                                type="button"
                                className="vs-btn-refresh"
                                onClick={handleManualRefresh}
                                disabled={refreshing}
                            >
                                <span className={refreshing ? 'vs-spin' : ''}>↻</span> Refresh
                            </button>
                        </div>
                    </div>

                    <div className="vs-table-scroll">
                        <table className="vs-table">
                            <thead>
                            <tr>
                                <th>Instrument</th>
                                <th>Status</th>
                                <th className="num">Live CP</th>
                                <th className="num">Live CV</th>
                                <th className="num">Spread</th>
                                <th>Hit Progress</th>
                                <th className="center">Entry Hits</th>
                                <th className="center">Exit Hits</th>
                                <th className="center">Telegram</th>
                                <th className="center">Monitor</th>
                                <th className="center"></th>
                            </tr>
                            </thead>
                            <tbody>
                            {watchlist.length === 0 ? (
                                <tr>
                                    <td colSpan="11" className="vs-empty-state">
                                        No strikes configured for today. Add a stock and strike above to start tracking.
                                    </td>
                                </tr>
                            ) : (
                                watchlist.map((row) => {
                                    const isSelected = row.id === selectedId;
                                    const target =
                                        row.status === 'IN_POSITION' ? row.exitHitTarget : row.entryHitTarget;
                                    const pct = Math.min(
                                        100,
                                        Math.round((row.currentHits / Math.max(1, target)) * 100)
                                    );
                                    const hasPrices = row.liveCp != null && row.liveCv != null;
                                    const diff = hasPrices ? Number(row.liveCp) - Number(row.liveCv) : null;
                                    const isAlertLoading = !!patching[`${row.id}-alertFlag`];
                                    const isActiveLoading = !!patching[`${row.id}-isActive`];

                                    return (
                                        <tr
                                            key={row.id}
                                            className={`vs-row ${isSelected ? 'selected' : ''} ${!row.isActive ? 'paused' : ''}`}
                                            onClick={() => setSelectedId(isSelected ? null : row.id)}
                                        >
                                            <td>
                                                <div className="vs-inst">
                            <span className="vs-inst-name">
                              {row.symbol}{' '}
                                <span className="vs-inst-strike">{Number(row.strike)}</span>
                            </span>
                                                    <span className="vs-inst-meta">
                              {row.expiry} {row.lastTickTime ? `• ${row.lastTickTime}` : ''}
                            </span>
                                                </div>
                                            </td>

                                            <td>
                          <span className={`vs-status-pill ${row.status.toLowerCase()}`}>
                            <span className="vs-dot" />
                              {row.status === 'IN_POSITION' ? 'IN POSITION' : 'WAITING'}
                          </span>
                                            </td>

                                            <td
                                                className={`num mono ${hasPrices ? (diff < 0 ? 'txt-green' : 'txt-red') : 'txt-muted'}`}
                                            >
                                                {row.liveCp != null ? Number(row.liveCp).toFixed(2) : '—'}
                                            </td>

                                            <td className="num mono txt-amber">
                                                {row.liveCv != null ? Number(row.liveCv).toFixed(2) : '—'}
                                            </td>

                                            <td className="num mono">
                                                {diff != null ? (
                                                    <span className={`vs-spread-chip ${diff < 0 ? 'below' : 'above'}`}>
                              {diff > 0 ? `+${diff.toFixed(2)}` : diff.toFixed(2)}
                            </span>
                                                ) : (
                                                    <span className="txt-muted">—</span>
                                                )}
                                            </td>

                                            <td>
                                                <div className="vs-progress-wrap">
                                                    <div className="vs-progress-track">
                                                        <div
                                                            className={`vs-progress-fill ${row.status === 'IN_POSITION' ? 'exit' : 'entry'}`}
                                                            style={{ width: `${pct}%` }}
                                                        />
                                                    </div>
                                                    <span className="vs-progress-text mono">
                              {row.currentHits}/{target}
                            </span>
                                                </div>
                                            </td>

                                            <td className="center" onClick={(e) => e.stopPropagation()}>
                                                <input
                                                    key={`en-${row.id}-${row.entryHitTarget}`}
                                                    type="number"
                                                    min="1"
                                                    className="vs-inline-input"
                                                    defaultValue={row.entryHitTarget}
                                                    onBlur={(e) => handleHitBlur(row, 'entryHitTarget', e.target.value)}
                                                    onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
                                                />
                                            </td>

                                            <td className="center" onClick={(e) => e.stopPropagation()}>
                                                <input
                                                    key={`ex-${row.id}-${row.exitHitTarget}`}
                                                    type="number"
                                                    min="1"
                                                    className="vs-inline-input"
                                                    defaultValue={row.exitHitTarget}
                                                    onBlur={(e) => handleHitBlur(row, 'exitHitTarget', e.target.value)}
                                                    onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
                                                />
                                            </td>

                                            <td className="center" onClick={(e) => e.stopPropagation()}>
                                                <button
                                                    type="button"
                                                    disabled={isAlertLoading}
                                                    className={`vs-switch ${row.alertFlag === 'Y' ? 'on' : ''} ${isAlertLoading ? 'loading' : ''}`}
                                                    onClick={() =>
                                                        handlePatch(
                                                            row.id,
                                                            { alertFlag: row.alertFlag === 'Y' ? 'N' : 'Y' },
                                                            'alertFlag'
                                                        )
                                                    }
                                                    title={`Telegram Alerts: ${row.alertFlag}`}
                                                >
                                                    {isAlertLoading ? (
                                                        <span className="vs-mini-spinner" />
                                                    ) : (
                                                        <span className="vs-switch-thumb" />
                                                    )}
                                                    <span className="vs-switch-lbl">{row.alertFlag}</span>
                                                </button>
                                            </td>

                                            <td className="center" onClick={(e) => e.stopPropagation()}>
                                                <button
                                                    type="button"
                                                    disabled={isActiveLoading}
                                                    className={`vs-switch monitor ${row.isActive ? 'on' : ''} ${isActiveLoading ? 'loading' : ''}`}
                                                    onClick={() =>
                                                        handlePatch(row.id, { isActive: !row.isActive }, 'isActive')
                                                    }
                                                    title={row.isActive ? 'Pause monitoring' : 'Resume monitoring'}
                                                >
                                                    {isActiveLoading ? (
                                                        <span className="vs-mini-spinner" />
                                                    ) : (
                                                        <span className="vs-switch-thumb" />
                                                    )}
                                                    <span className="vs-switch-lbl">{row.isActive ? 'ON' : 'OFF'}</span>
                                                </button>
                                            </td>

                                            <td className="center">
                                                <button
                                                    type="button"
                                                    className="vs-icon-btn delete"
                                                    title="Delete strike"
                                                    onClick={(e) => handleDelete(e, row.id)}
                                                >
                                                    ✕
                                                </button>
                                            </td>
                                        </tr>
                                    );
                                })
                            )}
                            </tbody>
                        </table>
                    </div>
                </section>

                {/* Chart Panel — Visible only when a row is selected */}
                {selectedItem && (
                    <StraddleChartPanel
                        item={selectedItem}
                        ticks={chartTicks}
                        onClose={() => setSelectedId(null)}
                    />
                )}
            </div>
        </div>
    );
}