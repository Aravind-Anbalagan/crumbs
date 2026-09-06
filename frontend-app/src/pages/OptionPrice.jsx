import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Activity, TrendingUp, Clock, Server, Zap, Search, RefreshCw, AlertCircle, Crosshair } from 'lucide-react';
import './OptionPrice.css';

// Safely extracts the underlying symbol (e.g., NIFTY) from a full option token
const extractBaseName = (sym) => {
  if (!sym) return null;
  const match = sym.match(/^[A-Z]+/i);
  return match ? match[0].toUpperCase() : sym;
};

const OptionPrice = () => {
  const [activeTab, setActiveTab] = useState('RSI');
  const [liveData, setLiveData] = useState([]);
  const [selectedSymbol, setSelectedSymbol] = useState(null);
  const [auditData, setAuditData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [initialLoading, setInitialLoading] = useState(true);
  const [timeFrame, setTimeFrame] = useState('FIFTEEN_MINUTE');
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

  // ========================================
  // EXTRACT STRICT UNIQUE BASE SYMBOLS
  // ========================================
  const availableSymbols = useMemo(() => {
    const rawNames = liveData.map(r => extractBaseName(r?.symbol)).filter(Boolean);
    const uniqueNames = [...new Set(rawNames)];
    return uniqueNames;
  }, [liveData]);

  useEffect(() => {
    if (availableSymbols.length > 0 && !dominanceSymbol) {
      setDominanceSymbol(availableSymbols[0]);
    }
  }, [availableSymbols, dominanceSymbol]);

  // Sync audit click to Dominance Bar Base Asset
  useEffect(() => {
    if (selectedSymbol) {
      const baseName = extractBaseName(selectedSymbol);
      if (baseName) setDominanceSymbol(baseName);
    }
  }, [selectedSymbol]);

  const fetchLive = useCallback(async (retries = 0) => {
    setIsRefreshing(true);
    setError(null);
    try {
      const endpoint = activeTab === 'RSI'
        ? '/api/options/scanner/tracked/live/rsi'
        : '/api/options/scanner/tracked/live/ma';

      const url = `${endpoint}?timeFrame=${timeFrame}`;
      const res = await fetch(url, { method: 'GET', headers: { 'Content-Type': 'application/json' } });

      if (!res.ok) throw new Error(`API Error: ${res.status} ${res.statusText}`);

      const data = await res.json();
      setLiveData(Array.isArray(data) ? data : []);
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
  }, [activeTab, timeFrame]);

  const fetchDominance = useCallback(async () => {
    const targetSymbol = dominanceSymbol || (availableSymbols.length > 0 ? availableSymbols[0] : null);
    if (!targetSymbol) return;

    try {
      const url = `/api/options/scanner/tracked/live/dominance?symbol=${targetSymbol}`;
      const res = await fetch(url);
      if (res.ok) {
        const data = await res.json();
        setDominance(data);
      }
    } catch (err) {
      console.error('[ERROR] fetchDominance failed:', err);
    }
  }, [dominanceSymbol, availableSymbols]);

  useEffect(() => {
    fetchLive();
    refreshIntervalRef.current = setInterval(fetchLive, 30000);
    return () => { if (refreshIntervalRef.current) clearInterval(refreshIntervalRef.current); };
  }, [activeTab, timeFrame, fetchLive]);

  useEffect(() => {
    if (availableSymbols.length > 0) {
      fetchDominance();
      dominanceIntervalRef.current = setInterval(fetchDominance, 30000);
    }
    return () => { if (dominanceIntervalRef.current) clearInterval(dominanceIntervalRef.current); };
  }, [fetchDominance, availableSymbols]);

  useEffect(() => {
    if (!selectedSymbol) return;

    const fetchAudit = async () => {
      setLoading(true);
      setError(null);
      try {
        const url = `/api/options/scanner/tracked/audit?symbol=${selectedSymbol}&timeFrame=${timeFrame}`;
        const res = await fetch(url);
        if (!res.ok) throw new Error(`API Error: ${res.status}`);
        const data = await res.json();
        setAuditData(Array.isArray(data) ? data : []);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    };

    fetchAudit();
  }, [selectedSymbol, timeFrame]);

  useEffect(() => {
    const handleKeyPress = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'r') {
        e.preventDefault();
        fetchLive();
        fetchDominance();
      }
      if (e.key === 'Escape') setSelectedSymbol(null);
    }
    window.addEventListener('keydown', handleKeyPress);
    return () => window.removeEventListener('keydown', handleKeyPress);
  }, [fetchLive, fetchDominance]);

  const getStyleClass = (action) => {
    if (!action || action === 'NONE') return 'op-type-ma';
    if (action.includes('OVERSOLD') || action === 'buy') return 'op-type-buy';
    if (action.includes('OVERBOUGHT') || action === 'sell') return 'op-type-sell';
    if (action.includes('MA') || action === 'ma') return 'op-type-ma';
    return 'op-type-info';
  };

  const getPillText = (action) => {
    if (!action || action === 'NONE') return 'MA BREAKOUT';
    return action.replace('TRIGGER_', '').replace('_HOOK', ' HOOK').replace(/_/g, ' ');
  };

  const getExtremeCount = (row) => {
    if (!row) return null;
    if (row.signalAction && row.signalAction.includes('OVERBOUGHT')) return row.aboveRSI80Count || 0;
    if (row.signalAction && row.signalAction.includes('OVERSOLD')) return row.belowRSI20Count || 0;
    return null;
  };

  const formatPrice = (value) => value == null ? '0.00' : parseFloat(value).toFixed(2);
  const formatRsi = (value) => value == null ? '--' : parseFloat(value).toFixed(1);
  const formatTime = (dateString) => dateString ? new Date(dateString).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '--:--';

  const filteredLiveData = useMemo(() => {
    return liveData.filter(row => {
      if (!searchTerm) return true;
      const term = searchTerm.toLowerCase();
      return ((row?.symbol && row.symbol.toLowerCase().includes(term)) || (row?.ltp && row.ltp.toString().includes(term)));
    });
  }, [liveData, searchTerm]);

  const sortedData = useMemo(() => {
    const sorted = [...filteredLiveData];
    switch (sortBy) {
      case 'price': return sorted.sort((a, b) => (b?.ltp || 0) - (a?.ltp || 0));
      case 'signal':
        const order = { 'op-type-buy': 0, 'op-type-sell': 1, 'op-type-ma': 2, 'op-type-info': 3 };
        return sorted.sort((a, b) => (order[getStyleClass(a?.signalAction)] || 999) - (order[getStyleClass(b?.signalAction)] || 999));
      case 'time': default:
        return sorted.sort((a, b) => new Date(b?.evaluatedAt || 0) - new Date(a?.evaluatedAt || 0));
    }
  }, [filteredLiveData, sortBy]);

  const signalStats = useMemo(() => {
    return {
      buy: sortedData.filter(r => r?.signalAction && r.signalAction.includes('OVERSOLD')).length,
      sell: sortedData.filter(r => r?.signalAction && r.signalAction.includes('OVERBOUGHT')).length,
      ma: sortedData.filter(r => !r?.signalAction || r.signalAction === 'NONE').length,
    };
  }, [sortedData]);

  const cePercent = dominance?.cePercentage ?? 50;
  const pePercent = dominance?.pePercentage ?? 50;

  return (
    <div className="op-container">
      {/* HEADER - TITLE + FULL WIDTH DOMINANCE BAR */}
      <div className="op-header">


        {/* Full Width Dominance Meter */}
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

      {/* ERROR BANNER */}
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

      {/* TWO-COLUMN DASHBOARD - RESPONSIVE */}
      <div className="op-layout">

        {/* LEFT PANEL */}
        <div className="op-left-panel">
          {/* TABS */}
          <div className="op-tabs">
            <button onClick={() => setActiveTab('RSI')} className={`op-tab-btn ${activeTab === 'RSI' ? 'active' : ''}`}><Activity size={16} /> RSI</button>
            <button onClick={() => setActiveTab('MA')} className={`op-tab-btn ${activeTab === 'MA' ? 'active' : ''}`}><TrendingUp size={16} /> MA</button>
          </div>

          {/* ACTION BAR - Search & Sort */}
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

          {/* STATS & REFRESH ROW */}
          <div className="op-controls-row">
            <div className="op-stat-pills-inline">
              <span title="Buy signals">🟢 {signalStats.buy}</span>
              <span title="Sell signals">🔴 {signalStats.sell}</span>
              <span title="MA breakouts">🔷 {signalStats.ma}</span>
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

          {/* RADAR LIST */}
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
              <div className="op-empty-message">No active signals currently matching criteria.</div>
            ) : (
              sortedData.map((row, idx) => {
                const count = getExtremeCount(row);
                return (
                  <div
                    key={`${row?.symbol || 'unknown'}-${idx}`}
                    onClick={() => setSelectedSymbol(row?.symbol)}
                    className={`op-radar-card ${selectedSymbol === row?.symbol ? 'active' : ''}`}
                    role="button"
                    tabIndex={0}
                  >
                    <div className="op-radar-card-top">
                      <h3>{row?.symbol || 'UNKNOWN'}</h3>
                      <span className={`op-pill ${getStyleClass(row?.signalAction)}`}>
                        {getPillText(row?.signalAction)}
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

        {/* RIGHT PANEL - AUDIT (Hidden on mobile, shown on desktop) */}
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
                  <p>Chronological Audit Trail (Newest First)</p>
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
                      const styleClass = getStyleClass(event?.signalAction);
                      const isMaBreakout = !event?.signalAction || event.signalAction === 'NONE';
                      const count = getExtremeCount(event);

                      return (
                        <div key={`audit-${idx}`} className="op-timeline-item">
                          <div className={`op-timeline-dot ${styleClass} ${isLatest ? 'pulse' : ''}`}></div>
                          <div className={`op-timeline-card ${isLatest ? `latest-record ${styleClass}-border` : ''}`}>
                            {isLatest && <div className="op-latest-badge"><Zap size={10} /> LATEST</div>}
                            <div className="op-timeline-card-top">
                              <h4 style={{ color: `var(--${styleClass}-color, inherit)` }}>{getPillText(event?.signalAction)}</h4>
                              <span className="op-timeline-time">{new Date(event?.evaluatedAt).toLocaleTimeString()}</span>
                            </div>
                            <div className="op-timeline-data">
                              <span>LTP: <span className="op-text-white">{formatPrice(event?.ltp)}</span></span>
                              {isMaBreakout ? (
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