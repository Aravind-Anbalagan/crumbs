import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Activity, TrendingUp, Clock, Server, Zap, Search, RefreshCw, AlertCircle, Crosshair } from 'lucide-react';
import './OptionPrice.css';

// Safely extracts the underlying symbol (e.g., NIFTY) from a full option token
const extractBaseName = (sym) => {
  if (!sym) return null;
  const match = sym.match(/^[A-Z]+/i);
  return match ? match[0].toUpperCase() : sym;
};

// Static timeframes matching AngelOne standards + ALL option
const AVAILABLE_TIME_FRAMES = ['ALL', 'ONE_MINUTE', 'FIVE_MINUTE', 'FIFTEEN_MINUTE', 'ONE_HOUR'];

// Live endpoint and audit endpoint may name these fields differently
// (ltp / evaluatedAt vs currentLtp / lastEvaluatedAt) - accept either one
const normalizeRow = (r) => ({
  ...r,
  ltp: r.ltp ?? r.currentLtp,
  evaluatedAt: r.evaluatedAt ?? r.lastEvaluatedAt,
});

// Set to true  -> Overbought tab shows every row where the API says rsiAbove80 === true
// Set to false -> Overbought is decided by signal / aboveRSI80Count instead
const OVERBOUGHT_FROM_API_FLAG = true;

const apiFlag = (row, camel, lower) => (row?.[camel] ?? row?.[lower]) === true;

const isRowOverbought = (row) => {
  if (OVERBOUGHT_FROM_API_FLAG && apiFlag(row, 'rsiAbove80', 'rsiabove80')) return true;
  const action = String(row?.signalAction || 'NONE').toUpperCase();
  if (action.includes('OVERSOLD')) return false;
  return action.includes('OVERBOUGHT') || Number(row?.aboveRSI80Count) > 0 || row?.aboveRSI80At != null;
};

const isRowOversold = (row) => {
  if (apiFlag(row, 'rsiBelow20', 'rsibelow20')) return true;
  const action = String(row?.signalAction || 'NONE').toUpperCase();
  if (action.includes('OVERBOUGHT') && !action.includes('OVERSOLD')) return false;
  return action.includes('OVERSOLD') || Number(row?.belowRSI20Count) > 0 || row?.belowRSI20At != null;
};

const OptionPrice = () => {
  const [activeTab, setActiveTab] = useState('RSI');
  const [subTab, setSubTab] = useState('ALL');

  const [liveData, setLiveData] = useState([]);
  const [selectedSymbol, setSelectedSymbol] = useState(null);
  const [selectedTf, setSelectedTf] = useState(null); // timeframe of the clicked row (needed when ALL is active)
  const [auditData, setAuditData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [initialLoading, setInitialLoading] = useState(true);

  // --- Timeframe State (Defaults to ONE_HOUR, uses static list) ---
  const [timeFrame, setTimeFrame] = useState('ONE_HOUR');

  const [searchTerm, setSearchTerm] = useState('');
  const [sortBy, setSortBy] = useState('time');
  const [error, setError] = useState(null);
  const [retryCount, setRetryCount] = useState(0);

  // --- Dominance State ---
  const [dominance, setDominance] = useState(null);
  const [dominanceSymbol, setDominanceSymbol] = useState('');

  const refreshIntervalRef = useRef(null);
  const dominanceIntervalRef = useRef(null);
  const lastFetchTimeRef = useRef(null);
  const reqIdRef = useRef(0);

  const availableSymbols = useMemo(() => {
    const rawNames = liveData.map(r => extractBaseName(r?.symbol)).filter(Boolean);
    const uniqueNames = [...new Set(rawNames)];
    return uniqueNames;
  }, [liveData]);
  const symbolsKey = availableSymbols.join(','); // stable value, only changes when the symbol list really changes

  useEffect(() => {
    if (availableSymbols.length > 0 && !dominanceSymbol) {
      setDominanceSymbol(availableSymbols[0]);
    }
  }, [availableSymbols, dominanceSymbol]);

  useEffect(() => {
    if (selectedSymbol) {
      const baseName = extractBaseName(selectedSymbol);
      if (baseName) setDominanceSymbol(baseName);
    }
  }, [selectedSymbol]);

  useEffect(() => {
    setSelectedSymbol(null);
    setSelectedTf(null);
    setAuditData([]);
    setSubTab('ALL');
  }, [activeTab]);

  // 1. Fetch Live Table Data - ONE call with timeFrame=ALL; timeframe buttons filter in the UI
  const fetchLive = useCallback(async (retries = 0) => {
    setIsRefreshing(true);
    setError(null);
    const reqId = ++reqIdRef.current;
    try {
      const endpoint = activeTab === 'RSI'
          ? '/api/options/scanner/tracked/live/rsi'
          : '/api/options/scanner/tracked/live/ma';

      const res = await fetch(`${endpoint}?timeFrame=ALL`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
      });
      if (!res.ok) throw new Error(`API Error: ${res.status} ${res.statusText}`);

      const data = await res.json();
      if (reqId !== reqIdRef.current) return; // a newer request started, ignore this stale response

      setLiveData(Array.isArray(data) ? data.map(normalizeRow) : []);
      lastFetchTimeRef.current = new Date();
      setRetryCount(0);
    } catch (err) {
      setError(err.message);
      if (retries < 2) {
        setTimeout(() => fetchLive(retries + 1), 2000 * (retries + 1));
        setRetryCount(retries + 1);
      }
    } finally {
      setIsRefreshing(false);
      setInitialLoading(false);
    }
  }, [activeTab]);

  // 2. Fetch Top Bar Dominance Stats
  const fetchDominance = useCallback(async () => {
    const targetSymbol = dominanceSymbol || (availableSymbols.length > 0 ? availableSymbols[0] : null);
    if (!targetSymbol) return;

    try {
      const url = `/api/options/scanner/tracked/live/dominance?symbol=${targetSymbol}`;
      const res = await fetch(url, { method: 'GET', headers: { 'Content-Type': 'application/json' } });
      if (res.ok) {
        const data = await res.json();
        setDominance(data);
      }
    } catch (err) {
      console.error('[ERROR] fetchDominance failed:', err);
    }
  }, [dominanceSymbol, symbolsKey]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    fetchLive();
    refreshIntervalRef.current = setInterval(fetchLive, 30000);
    return () => { if (refreshIntervalRef.current) clearInterval(refreshIntervalRef.current); };
  }, [activeTab, fetchLive]);

  useEffect(() => {
    if (symbolsKey) {
      fetchDominance();
      dominanceIntervalRef.current = setInterval(fetchDominance, 30000);
    }
    return () => { if (dominanceIntervalRef.current) clearInterval(dominanceIntervalRef.current); };
  }, [fetchDominance, symbolsKey]);

  // 3. Fetch Side Panel Audit Timeline
  useEffect(() => {
    if (!selectedSymbol) return;

    const fetchAudit = async () => {
      setLoading(true);
      setError(null);
      try {
        // When ALL is active, use the timeframe of the clicked row; audit endpoint can't take ALL
        const tf = selectedTf || (timeFrame === 'ALL' ? 'ONE_HOUR' : timeFrame);
        const url = `/api/options/scanner/tracked/audit?symbol=${selectedSymbol}&timeFrame=${tf}`;
        const res = await fetch(url, { method: 'GET', headers: { 'Content-Type': 'application/json' } });
        if (!res.ok) throw new Error(`API Error: ${res.status}`);
        const data = await res.json();
        setAuditData(Array.isArray(data) ? data.map(normalizeRow) : []);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    };

    fetchAudit();
  }, [selectedSymbol, selectedTf, timeFrame]);

  useEffect(() => {
    const handleKeyPress = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'r') {
        e.preventDefault();
        fetchLive();
        fetchDominance();
      }
      if (e.key === 'Escape') {
        setSelectedSymbol(null);
        setSelectedTf(null);
      }
    }
    window.addEventListener('keydown', handleKeyPress);
    return () => window.removeEventListener('keydown', handleKeyPress);
  }, [fetchLive, fetchDominance]);

  const getStyleClass = (row, currentTab) => {
    if (!row) return 'op-type-info';
    if (currentTab === 'MA') {
      return row.priceAboveMa ? 'op-type-ma-up' : 'op-type-ma-down';
    }
    if (isRowOversold(row)) return 'op-type-buy';
    if (isRowOverbought(row)) return 'op-type-sell';

    return 'op-type-info';
  };

  const getPillText = (row, currentTab) => {
    if (!row) return 'UNKNOWN';
    if (currentTab === 'MA') {
      return row.priceAboveMa ? 'MA BREAKOUT' : 'MA BREAKDOWN';
    }

    const action = (row.signalAction || 'NONE').toUpperCase();
    if (action !== 'NONE') {
      return action.replace('TRIGGER_', '').replace('_HOOK', ' HOOK').replace(/_/g, ' ');
    }

    if (isRowOverbought(row)) return 'OVERBOUGHT';
    if (isRowOversold(row)) return 'OVERSOLD';

    return 'NEUTRAL';
  };

  const getExtremeCount = (row) => {
    if (!row) return null;
    if (isRowOverbought(row)) return row.aboveRSI80Count || 0;
    if (isRowOversold(row)) return row.belowRSI20Count || 0;
    return null;
  };

  const formatPrice = (value) => value == null ? '0.00' : parseFloat(value).toFixed(2);
  const formatRsi = (value) => value == null ? '--' : parseFloat(value).toFixed(1);
  const formatTime = (dateString) => dateString ? new Date(dateString).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '--:--';

  // Timeframe selector is applied here, in the UI
  const timeFrameData = useMemo(
      () => (timeFrame === 'ALL' ? liveData : liveData.filter(r => r?.timeFrame === timeFrame)),
      [liveData, timeFrame]
  );

  const filteredLiveData = useMemo(() => {
    return timeFrameData.filter(row => {
      if (searchTerm) {
        const term = searchTerm.toLowerCase();
        const matchesSearch = ((row?.symbol && row.symbol.toLowerCase().includes(term)) || (row?.ltp && row.ltp.toString().includes(term)));
        if (!matchesSearch) return false;
      }

      if (subTab !== 'ALL') {
        if (activeTab === 'RSI') {
          if (subTab === 'OVERSOLD' && !isRowOversold(row)) return false;
          if (subTab === 'OVERBOUGHT' && !isRowOverbought(row)) return false;
        } else if (activeTab === 'MA') {
          if (subTab === 'BREAKOUT' && row?.priceAboveMa !== true) return false;
          if (subTab === 'BREAKDOWN' && row?.priceAboveMa !== false) return false;
        }
      }
      return true;
    });
  }, [timeFrameData, searchTerm, subTab, activeTab]);

  const sortedData = useMemo(() => {
    const sorted = [...filteredLiveData];
    switch (sortBy) {
      case 'price': return sorted.sort((a, b) => (b?.ltp || 0) - (a?.ltp || 0));
      case 'signal':
        const order = { 'op-type-buy': 0, 'op-type-sell': 1, 'op-type-ma-up': 2, 'op-type-ma-down': 3, 'op-type-info': 4 };
        return sorted.sort((a, b) => (order[getStyleClass(a, activeTab)] || 999) - (order[getStyleClass(b, activeTab)] || 999));
      case 'time': default:
        return sorted.sort((a, b) => new Date(b?.evaluatedAt || 0) - new Date(a?.evaluatedAt || 0));
    }
  }, [filteredLiveData, sortBy, activeTab]);

  const signalStats = useMemo(() => {
    // Calculated from 'liveData' instead of 'sortedData' so these stats represent the TOTAL
    // available records, and don't drop to 0 when you click on a filter tab.
    return {
      buy: timeFrameData.filter(r => isRowOversold(r)).length,
      sell: timeFrameData.filter(r => isRowOverbought(r)).length,
      maBreakout: timeFrameData.filter(r => r?.priceAboveMa === true).length,
      maBreakdown: timeFrameData.filter(r => r?.priceAboveMa === false).length,
    };
  }, [timeFrameData]);

  const cePercent = dominance?.cePercentage ?? 50;
  const pePercent = dominance?.pePercentage ?? 50;

  return (
      <div className="op-container">
        <div className="op-header">
          <div className="op-header-dominance">
            <div className="op-header-dominance-labels">
              <span className="op-text-green">CE ({dominance?.ceCount || 0})</span>
              <select
                  className="op-dominance-select"
                  value={dominanceSymbol || ''}
                  onChange={(e) => setDominanceSymbol(e.target.value)}
                  disabled={availableSymbols.length === 0}
              >
                {availableSymbols.length === 0 ? (
                    <option value="">Awaiting Data...</option>
                ) : (
                    availableSymbols.map(sym => <option key={sym} value={sym}>{sym}</option>)
                )}
              </select>
              <span className="op-text-red">PE ({dominance?.peCount || 0})</span>
            </div>
            <div className="op-header-dominance-bar">
              <div className="op-header-ce-fill" style={{ width: `${cePercent}%` }}>
                {cePercent > 10 && dominance && `${cePercent}%`}
              </div>
              <div className="op-header-pe-fill" style={{ width: `${pePercent}%` }}>
                {pePercent > 10 && dominance && `${pePercent}%`}
              </div>
            </div>
          </div>
        </div>

        {error && (
            <div className="op-error-banner">
              <AlertCircle size={16} />
              <span>{error}</span>
              {retryCount > 0 && <span className="op-error-retry">Retry {retryCount}/2...</span>}
              <button onClick={() => { setError(null); fetchLive(); }} style={{ marginLeft: 'auto', padding: '2px 8px', fontSize: '12px', background: 'rgba(255,255,255,0.1)', border: 'none', color: 'inherit', borderRadius: '4px', cursor: 'pointer' }}>
                Dismiss
              </button>
            </div>
        )}

        <div className="op-layout">
          <div className="op-left-panel">
            <div className="op-tabs">
              <button onClick={() => setActiveTab('RSI')} className={`op-tab-btn ${activeTab === 'RSI' ? 'active' : ''}`}><Activity size={16} /> RSI Signals</button>
              <button onClick={() => setActiveTab('MA')} className={`op-tab-btn ${activeTab === 'MA' ? 'active' : ''}`}><TrendingUp size={16} /> MA Signals</button>
            </div>

            <div className="op-sub-tabs">
              <button onClick={() => setSubTab('ALL')} className={`op-sub-tab-btn ${subTab === 'ALL' ? 'active' : ''}`}>
                All
              </button>
              {activeTab === 'RSI' ? (
                  <>
                    <button onClick={() => setSubTab('OVERSOLD')} className={`op-sub-tab-btn buy ${subTab === 'OVERSOLD' ? 'active' : ''}`}>
                      Oversold
                    </button>
                    <button onClick={() => setSubTab('OVERBOUGHT')} className={`op-sub-tab-btn sell ${subTab === 'OVERBOUGHT' ? 'active' : ''}`}>
                      Overbought
                    </button>
                  </>
              ) : (
                  <>
                    <button onClick={() => setSubTab('BREAKOUT')} className={`op-sub-tab-btn up ${subTab === 'BREAKOUT' ? 'active' : ''}`}>
                      Breakout
                    </button>
                    <button onClick={() => setSubTab('BREAKDOWN')} className={`op-sub-tab-btn down ${subTab === 'BREAKDOWN' ? 'active' : ''}`}>
                      Breakdown
                    </button>
                  </>
              )}
            </div>

            {/* STATIC TIMEFRAME SELECTOR */}
            <div className="op-timeframe-selector">
              {AVAILABLE_TIME_FRAMES.map((tf) => (
                  <label
                      key={tf}
                      className={`op-tf-radio ${timeFrame === tf ? 'active' : ''}`}
                  >
                    <input
                        type="radio"
                        name="timeframe"
                        value={tf}
                        checked={timeFrame === tf}
                        onChange={(e) => { setTimeFrame(e.target.value); setSelectedSymbol(null); setSelectedTf(null); }}
                        style={{ display: 'none' }}
                    />
                    {tf.replace(/_/g, ' ')}
                  </label>
              ))}
            </div>

            <div className="op-action-bar">
              <div className="op-search-controls">
                <div className="op-search-bar">
                  <Search size={14} className="op-search-icon" />
                  <input type="text" className="op-search-input" placeholder="Filter..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} />
                </div>
                <div className="op-sort-dropdown">
                  <select value={sortBy} onChange={(e) => setSortBy(e.target.value)}>
                    <option value="time">Time</option>
                    <option value="price">Price</option>
                    <option value="signal">Signal</option>
                  </select>
                </div>
              </div>
            </div>

            <div className="op-controls-row">
              <div className="op-stat-pills-inline">
                {activeTab === 'RSI' ? (
                    <>
                      <span title="Buy signals">🟢 {signalStats.buy}</span>
                      <span title="Sell signals">🔴 {signalStats.sell}</span>
                    </>
                ) : (
                    <>
                      <span title="Breakouts">📈 {signalStats.maBreakout}</span>
                      <span title="Breakdowns">📉 {signalStats.maBreakdown}</span>
                    </>
                )}
              </div>
              <button
                  onClick={() => { fetchLive(); fetchDominance(); }}
                  disabled={isRefreshing}
                  className="op-refresh-btn"
                  title="Ctrl+R to refresh"
              >
                <RefreshCw size={14} className={isRefreshing ? 'op-spin' : ''} />
                {isRefreshing ? 'Scanning...' : 'Refresh'}
              </button>
            </div>

            <div className="op-radar-list op-scrollbar">
              {initialLoading && sortedData.length === 0 ? (
                  <div className="op-loader-container">
                    <div className="op-spinner"></div>
                    <span>Loading market data...</span>
                  </div>
              ) : isRefreshing && sortedData.length === 0 ? (
                  <div className="op-loader-container">
                    <div className="op-spinner"></div>
                    <span>Scanning market...</span>
                  </div>
              ) : sortedData.length === 0 ? (
                  <div className="op-empty-message">No active signals currently matching criteria. (Loaded {liveData.length} rows from API, {timeFrameData.length} for this timeframe)</div>
              ) : (
                  sortedData.map((row, idx) => {
                    const count = getExtremeCount(row);
                    const isActive = selectedSymbol === row?.symbol && selectedTf === row?.timeFrame;
                    return (
                        <div
                            key={`${row?.symbol || 'unknown'}-${row?.timeFrame || 'tf'}-${idx}`}
                            onClick={() => { setSelectedSymbol(row?.symbol); setSelectedTf(row?.timeFrame); }}
                            className={`op-radar-card ${isActive ? 'active' : ''}`}
                            role="button"
                            tabIndex={0}
                        >
                          <div className="op-radar-card-top">
                            <h3>{row?.symbol || 'UNKNOWN'}</h3>
                            <span className={`op-pill ${getStyleClass(row, activeTab)}`}>
                        {getPillText(row, activeTab)}
                      </span>
                          </div>
                          <div className="op-radar-card-bottom">
                            <span>LTP: <span className="op-text-white">{formatPrice(row?.ltp)}</span></span>
                            {activeTab === 'RSI' ? (
                                <>
                                  <span>RSI: <span className={row?.currentRsi > 70 ? 'op-text-red' : row?.currentRsi < 30 ? 'op-text-green' : 'op-text-white'}>{formatRsi(row?.currentRsi)}</span></span>
                                  {count !== null && <span>Cnt: <span className="op-text-white">{count}</span></span>}
                                </>
                            ) : (
                                <span>MA: <span className="op-text-cyan">{formatPrice(row?.currentMa)}</span></span>
                            )}
                            {row?.timeFrame && (
                                <span className="op-text-cyan">{row.timeFrame.replace(/_/g, ' ')}</span>
                            )}
                            <span className="op-radar-time">
                        <Clock size={12} /> {formatTime(row?.evaluatedAt)}
                      </span>
                          </div>
                        </div>
                    );
                  })
              )}
            </div>
          </div>

          <div className="op-right-panel">
            {!selectedSymbol ? (
                <div className="op-empty-state">
                  <Crosshair className="op-empty-icon" size={48} />
                  <p>Select a contract from the radar to view its lifecycle audit.</p>
                </div>
            ) : (
                <>
                  <div className="op-detail-header">
                    <div>
                      <h2>{selectedSymbol}</h2>
                      <p>Chronological Audit Trail (Newest First){selectedTf ? ` · ${selectedTf.replace(/_/g, ' ')}` : ''}</p>
                    </div>
                  </div>
                  <div className="op-timeline-wrap op-scrollbar">
                    <div className="op-timeline-track">
                      {loading ? (
                          <div className="op-loader-container"><div className="op-spinner"></div></div>
                      ) : auditData.length === 0 ? (
                          <div className="op-timeline-empty">No historical data found.</div>
                      ) : (
                          [...auditData].reverse().map((event, idx) => {
                            const isLatest = idx === 0;
                            const isMaSignal = activeTab === 'MA';
                            const count = getExtremeCount(event);
                            const styleClass = getStyleClass(event, activeTab);

                            return (
                                <div key={`audit-${idx}`} className="op-timeline-item">
                                  <div className={`op-timeline-dot ${styleClass} ${isLatest ? 'pulse' : ''}`}></div>
                                  <div className={`op-timeline-card ${isLatest ? `latest-record ${styleClass}-border` : ''}`}>
                                    {isLatest && <div className="op-latest-badge"><Zap size={10} /> LATEST</div>}
                                    <div className="op-timeline-card-top">
                                      <h4 style={{ color: `var(--${styleClass}-color, inherit)` }}>{getPillText(event, activeTab)}</h4>
                                      <span className="op-timeline-time">{event?.evaluatedAt ? new Date(event.evaluatedAt).toLocaleTimeString() : '--:--'}</span>
                                    </div>
                                    <div className="op-timeline-data">
                                      <span>LTP: <span className="op-text-white">{formatPrice(event?.ltp)}</span></span>
                                      {isMaSignal ? (
                                          <span>MA: <span className="op-text-cyan">{formatPrice(event?.currentMa)}</span></span>
                                      ) : (
                                          <>
                                            <span>RSI: <span className={event?.currentRsi > 70 ? 'op-text-red' : event?.currentRsi < 30 ? 'op-text-green' : 'op-text-white'}>{formatRsi(event?.currentRsi)}</span></span>
                                            {count !== null && count > 0 && <span>Cnt: <span className="op-text-white">{count}</span></span>}
                                          </>
                                      )}
                                    </div>
                                  </div>
                                </div>
                            );
                          })
                      )}
                    </div>
                  </div>
                </>
            )}
          </div>
        </div>
      </div>
  );
};

export default OptionPrice;