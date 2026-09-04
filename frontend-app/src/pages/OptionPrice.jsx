import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Activity, TrendingUp, Clock, Crosshair, Server, Zap, Search, RefreshCw, ChevronDown, AlertCircle } from 'lucide-react';
import './OptionPrice.css';

const OptionPrice = () => {
  const [activeTab, setActiveTab] = useState('RSI');
  const [liveData, setLiveData] = useState([]);
  const [selectedSymbol, setSelectedSymbol] = useState(null);
  const [auditData, setAuditData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [timeFrame, setTimeFrame] = useState('FIFTEEN_MINUTE');
  const [searchTerm, setSearchTerm] = useState('');
  const [sortBy, setSortBy] = useState('time'); // time, price, signal
  const [error, setError] = useState(null);
  const [retryCount, setRetryCount] = useState(0);
  const refreshIntervalRef = useRef(null);
  const lastFetchTimeRef = useRef(null);

  // Extract fetch function with retry logic
  const fetchLive = useCallback(async (retries = 0) => {
    setIsRefreshing(true);
    setError(null);

    try {
      const endpoint = activeTab === 'RSI'
        ? '/api/options/scanner/tracked/live/rsi'
        : '/api/options/scanner/tracked/live/ma';

      const res = await fetch(`${endpoint}?timeFrame=${timeFrame}`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
      });

      if (!res.ok) throw new Error(`API Error: ${res.status}`);

      const data = await res.json();
      setLiveData(Array.isArray(data) ? data : []);
      lastFetchTimeRef.current = new Date();
      setRetryCount(0);
    } catch (err) {
      console.error("Failed to load live data:", err);
      setError(err.message);

      // Auto-retry up to 2 times
      if (retries < 2) {
        setTimeout(() => fetchLive(retries + 1), 2000 * (retries + 1));
        setRetryCount(retries + 1);
      }
    } finally {
      setIsRefreshing(false);
    }
  }, [activeTab, timeFrame]);

  // Auto-refresh every 30 seconds
  useEffect(() => {
    fetchLive();
    refreshIntervalRef.current = setInterval(() => {
      fetchLive();
    }, 30000);

    return () => clearInterval(refreshIntervalRef.current);
  }, [activeTab, timeFrame, fetchLive]);

  // Fetch audit data
  useEffect(() => {
    if (!selectedSymbol) return;

    const fetchAudit = async () => {
      setLoading(true);
      setError(null);
      try {
        const res = await fetch(`/api/options/scanner/tracked/audit?symbol=${selectedSymbol}&timeFrame=${timeFrame}`);
        if (!res.ok) throw new Error(`API Error: ${res.status}`);
        const data = await res.json();
        setAuditData(Array.isArray(data) ? data : []);
      } catch (err) {
        console.error("Failed to load audit data:", err);
        setError(err.message);
      } finally {
        setLoading(false);
      }
    };

    fetchAudit();
  }, [selectedSymbol, timeFrame]);

  // Keyboard shortcuts
  useEffect(() => {
    const handleKeyPress = (e) => {
      if (e.ctrlKey || e.metaKey) {
        if (e.key === 'r') {
          e.preventDefault();
          fetchLive();
        }
      }
      if (e.key === 'Escape') {
        setSelectedSymbol(null);
      }
    };

    window.addEventListener('keydown', handleKeyPress);
    return () => window.removeEventListener('keydown', handleKeyPress);
  }, [fetchLive]);

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
    if (row.signalAction && row.signalAction.includes('OVERBOUGHT')) return row.aboveRSI80Count || 0;
    if (row.signalAction && row.signalAction.includes('OVERSOLD')) return row.belowRSI20Count || 0;
    return null;
  };

  // Enhanced filtering
  const filteredLiveData = useMemo(() => {
    return liveData.filter(row => {
      if (!searchTerm) return true;
      const term = searchTerm.toLowerCase();
      return (
        (row.symbol && row.symbol.toLowerCase().includes(term)) ||
        (row.ltp && row.ltp.toString().includes(term))
      );
    });
  }, [liveData, searchTerm]);

  // Sorting
  const sortedData = useMemo(() => {
    const sorted = [...filteredLiveData];

    switch (sortBy) {
      case 'price':
        return sorted.sort((a, b) => (b.ltp || 0) - (a.ltp || 0));
      case 'signal':
        const signalOrder = { 'op-type-buy': 0, 'op-type-sell': 1, 'op-type-ma': 2, 'op-type-info': 3 };
        return sorted.sort((a, b) =>
          (signalOrder[getStyleClass(a.signalAction)] || 999) -
          (signalOrder[getStyleClass(b.signalAction)] || 999)
        );
      case 'time':
      default:
        return sorted.sort((a, b) =>
          new Date(b.evaluatedAt || 0) - new Date(a.evaluatedAt || 0)
        );
    }
  }, [filteredLiveData, sortBy]);

  const signalStats = useMemo(() => {
    return {
      buy: sortedData.filter(r => r.signalAction && r.signalAction.includes('OVERSOLD')).length,
      sell: sortedData.filter(r => r.signalAction && r.signalAction.includes('OVERBOUGHT')).length,
      ma: sortedData.filter(r => !r.signalAction || r.signalAction === 'NONE').length,
    };
  }, [sortedData]);

  return (
    <div className="op-container">
      {/* PAGE HEADER */}
      <div className="op-header">
        <div className="op-header-title">
          <h2>
            <Crosshair size={24} />
            Live Scanner
          </h2>
          {lastFetchTimeRef.current && (
            <p className="op-last-updated">
              Last updated: {lastFetchTimeRef.current.toLocaleTimeString()}
            </p>
          )}
        </div>

        <div className="op-header-controls">
          <div className="op-stat-pills">
            <span title="Buy signals">🟢 {signalStats.buy}</span>
            <span title="Sell signals">🔴 {signalStats.sell}</span>
            <span title="MA breakouts">🔷 {signalStats.ma}</span>
          </div>

          <button
            onClick={() => fetchLive()}
            disabled={isRefreshing}
            className="op-refresh-btn"
            title="Ctrl+R to refresh"
          >
            <RefreshCw size={16} className={isRefreshing ? 'op-spin' : ''} />
            {isRefreshing ? 'Scanning...' : 'Refresh'}
          </button>

          <div className={`op-status-badge ${isRefreshing ? 'refreshing' : ''}`}>
            <Server size={14} /> Engine Active
          </div>

          <select
            className="op-select"
            value={timeFrame}
            onChange={(e) => setTimeFrame(e.target.value)}
          >
            <option value="FIFTEEN_MINUTE">15m</option>
            <option value="ONE_HOUR">1h</option>
          </select>
        </div>
      </div>

      {/* ERROR BANNER */}
      {error && (
        <div className="op-error-banner">
          <AlertCircle size={16} />
          <span>{error}</span>
          {retryCount > 0 && <span className="op-error-retry">Retry {retryCount}/2...</span>}
        </div>
      )}

      <div className="op-layout">
        {/* LEFT PANEL: LIVE RADAR */}
        <div className="op-left-panel">
          <div className="op-tabs">
            <button
              onClick={() => setActiveTab('RSI')}
              className={`op-tab-btn ${activeTab === 'RSI' ? 'active' : ''}`}
            >
              <Activity size={16} /> RSI Extremes
            </button>
            <button
              onClick={() => setActiveTab('MA')}
              className={`op-tab-btn ${activeTab === 'MA' ? 'active' : ''}`}
            >
              <TrendingUp size={16} /> MA Breakouts
            </button>
          </div>

          <div className="op-search-controls">
            <div className="op-search-bar">
              <Search size={16} />
              <input
                type="text"
                className="op-search-input"
                placeholder="Filter by symbol or price..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
              />
            </div>

            <div className="op-sort-dropdown">
              <select value={sortBy} onChange={(e) => setSortBy(e.target.value)}>
                <option value="time">Sort: Time</option>
                <option value="price">Sort: Price</option>
                <option value="signal">Sort: Signal</option>
              </select>
            </div>
          </div>

          <div className="op-radar-list op-scrollbar">
            {isRefreshing && sortedData.length === 0 ? (
              <div className="op-loader-container">
                <div className="op-spinner"></div>
                <span>Scanning market...</span>
              </div>
            ) : sortedData.length === 0 ? (
              <div className="op-empty-message">
                {searchTerm
                  ? 'No matches found for your filter.'
                  : 'No active signals currently matching criteria.'}
              </div>
            ) : (
              sortedData.map((row, idx) => {
                const count = getExtremeCount(row);

                return (
                  <div
                    key={`${row.symbol}-${idx}`}
                    onClick={() => setSelectedSymbol(row.symbol)}
                    className={`op-radar-card ${selectedSymbol === row.symbol ? 'active' : ''}`}
                    role="button"
                    tabIndex={0}
                  >
                    <div className="op-radar-card-top">
                      <h3>{row.symbol}</h3>
                      <span className={`op-pill ${getStyleClass(row.signalAction)}`}>
                        {getPillText(row.signalAction)}
                      </span>
                    </div>

                    <div className="op-radar-card-bottom">
                      <span>LTP: <span className="op-text-white">{row.ltp != null ? row.ltp.toFixed(2) : '0.00'}</span></span>

                      {activeTab === 'RSI' ? (
                        <>
                          <span>
                            RSI:{' '}
                            <span
                              className={
                                row.currentRsi > 70
                                  ? 'op-text-red'
                                  : row.currentRsi < 30
                                  ? 'op-text-green'
                                  : 'op-text-white'
                              }
                            >
                              {row.currentRsi != null ? row.currentRsi.toFixed(1) : '--'}
                            </span>
                          </span>
                          {count !== null && (
                            <span title="Candles in extreme zone">
                              Cnt: <span className="op-text-white">{count}</span>
                            </span>
                          )}
                        </>
                      ) : (
                        <span>
                          MA: <span className="op-text-cyan">{row.currentMa != null ? row.currentMa.toFixed(2) : '--'}</span>
                        </span>
                      )}

                      <span className="op-radar-time">
                        <Clock size={12} /> {row.evaluatedAt ? new Date(row.evaluatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '--:--'}
                      </span>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* RIGHT PANEL: AUDIT TIMELINE */}
        <div className="op-right-panel">
          {!selectedSymbol ? (
            <div className="op-empty-state">
              <Crosshair className="op-empty-icon" size={48} />
              <p>Select a contract from the radar to view its lifecycle audit.</p>
              <p className="op-empty-tip">Tip: Press ESC to deselect</p>
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
                    <div className="op-loader-container">
                      <div className="op-spinner"></div>
                      <span>Fetching audit trail...</span>
                    </div>
                  ) : auditData.length === 0 ? (
                    <div className="op-timeline-empty">No historical data found.</div>
                  ) : (
                    [...auditData].reverse().map((event, idx) => {
                      const isLatest = idx === 0;
                      const styleClass = getStyleClass(event.signalAction);
                      const count = getExtremeCount(event);
                      const isMaBreakout = !event.signalAction || event.signalAction === 'NONE';

                      return (
                        <div key={`audit-${idx}`} className="op-timeline-item">
                          <div className={`op-timeline-dot ${styleClass} ${isLatest ? 'pulse' : ''}`}></div>

                          <div className={`op-timeline-card ${isLatest ? `latest-record ${styleClass}-border` : ''}`}>
                            {isLatest && (
                              <div className="op-latest-badge">
                                <Zap size={10} /> LATEST
                              </div>
                            )}

                            <div className="op-timeline-card-top">
                              <h4 style={{ color: `var(--${styleClass}-color, inherit)` }}>
                                {getPillText(event.signalAction)}
                              </h4>
                              <span className="op-timeline-time">
                                {event.evaluatedAt
                                  ? new Date(event.evaluatedAt).toLocaleTimeString()
                                  : '--:--:--'}
                              </span>
                            </div>

                            <div className="op-timeline-data">
                              <span>LTP: <span className="op-text-white">{event.ltp != null ? event.ltp.toFixed(2) : '0.00'}</span></span>

                              {isMaBreakout ? (
                                <span>MA: <span className="op-text-cyan">{event.currentMa != null ? event.currentMa.toFixed(2) : '--'}</span></span>
                              ) : (
                                <>
                                  <span>
                                    RSI:{' '}
                                    <span
                                      className={
                                        event.currentRsi > 70
                                          ? 'op-text-red'
                                          : event.currentRsi < 30
                                          ? 'op-text-green'
                                          : 'op-text-white'
                                      }
                                    >
                                      {event.currentRsi != null ? event.currentRsi.toFixed(1) : '--'}
                                    </span>
                                  </span>

                                  {count !== null && count > 0 && (
                                    <span>Cnt: <span className="op-text-white">{count}</span></span>
                                  )}
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