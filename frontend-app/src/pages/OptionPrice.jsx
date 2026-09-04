import React, { useState, useEffect } from 'react';
import { Activity, TrendingUp, Clock, Crosshair, Server, Zap, Search } from 'lucide-react';
import './OptionPrice.css';

const OptionPrice = () => {
  const [activeTab, setActiveTab] = useState('RSI');
  const [liveData, setLiveData] = useState([]);
  const [selectedSymbol, setSelectedSymbol] = useState(null);
  const [auditData, setAuditData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [timeFrame, setTimeFrame] = useState('FIFTEEN_MINUTE');

  // 👇 New state for our Smart Filter
  const [searchTerm, setSearchTerm] = useState('');

  // FETCH LIVE DATA
  useEffect(() => {
    const fetchLive = async () => {
      try {
        const endpoint = activeTab === 'RSI' ? '/api/options/scanner/tracked/live/rsi' : '/api/options/scanner/tracked/live/ma';
        const res = await fetch(`http://localhost:8080${endpoint}?timeFrame=${timeFrame}`);
        const data = await res.json();
        setLiveData(data || []);
      } catch (err) {
        console.error("Failed to load live data:", err);
      }
    };

    fetchLive();
    const intervalId = setInterval(fetchLive, 15000);
    return () => clearInterval(intervalId);
  }, [activeTab, timeFrame]);

  // FETCH AUDIT DATA
  useEffect(() => {
    if (!selectedSymbol) return;

    const fetchAudit = async () => {
      setLoading(true);
      try {
        const res = await fetch(`http://localhost:8080/api/options/scanner/tracked/audit?symbol=${selectedSymbol}&timeFrame=${timeFrame}`);
        const data = await res.json();
        setAuditData(data || []);
      } catch (err) {
        console.error("Failed to load audit data:", err);
      } finally {
        setLoading(false);
      }
    };

    fetchAudit();
  }, [selectedSymbol, timeFrame]);

  // UI Helpers
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

  // 👇 The Smart Filter Logic
  const filteredLiveData = liveData.filter(row => {
    if (!searchTerm) return true;
    const term = searchTerm.toLowerCase();
    // Searches across symbol (CRUDEOILM17SEP269050PE includes name, expiry, strike, and type)
    return row.symbol && row.symbol.toLowerCase().includes(term);
  });

  return (
    <div className="op-container">
      {/* PAGE HEADER */}
      <div className="op-header">
        <h2>
          <Crosshair className="op-text-cyan" size={20} />
          Live Scanner
        </h2>
        <div className="op-header-controls">
          <div className="op-status-badge">
            <Server size={14}/> Engine Active
          </div>
          <select
            className="op-select"
            value={timeFrame}
            onChange={(e) => setTimeFrame(e.target.value)}
          >
            <option value="FIFTEEN_MINUTE">15 MINUTE</option>
            <option value="ONE_HOUR">1 HOUR</option>
          </select>
        </div>
      </div>

      <div className="op-layout">

        {/* LEFT PANEL: LIVE RADAR */}
        <div className="op-left-panel">

          {/* Tabs */}
          <div className="op-tabs">
            <button
              onClick={() => setActiveTab('RSI')}
              className={`op-tab-btn ${activeTab === 'RSI' ? 'active' : ''}`}
            >
              <Activity size={16}/> RSI Extremes
            </button>
            <button
              onClick={() => setActiveTab('MA')}
              className={`op-tab-btn ${activeTab === 'MA' ? 'active' : ''}`}
            >
              <TrendingUp size={16}/> MA Breakouts
            </button>
          </div>

          {/* 👇 Smart Search Bar */}
          <div className="op-search-bar">
            <Search className="op-search-icon" size={16} />
            <input
              type="text"
              className="op-search-input"
              placeholder="Filter by NIFTY, 19500, SEP..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
            />
          </div>

          <div className="op-radar-list op-scrollbar">
            {filteredLiveData.length === 0 ? (
              <div className="op-empty-message">
                {searchTerm ? 'No matches found for your filter.' : 'No active signals currently matching criteria.'}
              </div>
            ) : (
              filteredLiveData.map((row, idx) => {
                const count = getExtremeCount(row);

                return (
                  <div
                    key={idx}
                    onClick={() => setSelectedSymbol(row.symbol)}
                    className={`op-radar-card ${selectedSymbol === row.symbol ? 'active' : ''}`}
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
                          <span>RSI: <span className={row.currentRsi > 70 ? 'op-text-red' : row.currentRsi < 30 ? 'op-text-green' : 'op-text-white'}>
                            {row.currentRsi != null ? row.currentRsi.toFixed(1) : '--'}
                          </span></span>
                          {count !== null && <span title="Candles spent in extreme zone">Cnt: <span className="op-text-white">{count}</span></span>}
                        </>
                      ) : (
                        <span>MA: <span className="op-text-cyan">{row.currentMa != null ? row.currentMa.toFixed(2) : '--'}</span></span>
                      )}

                      <span style={{ display: 'flex', alignItems: 'center', gap: '4px', marginLeft: 'auto' }}>
                        <Clock size={12}/> {row.evaluatedAt ? new Date(row.evaluatedAt).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'}) : '--:--'}
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
              <Crosshair style={{ marginBottom: '1rem', opacity: 0.2 }} size={48}/>
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
                    <div style={{ paddingLeft: '1.5rem', color: 'var(--text-muted)' }}>Loading audit trail...</div>
                  ) : auditData.length === 0 ? (
                    <div style={{ paddingLeft: '1.5rem', color: 'var(--text-muted)' }}>No historical data found.</div>
                  ) : (
                    [...auditData].reverse().map((event, idx) => {
                      const isLatest = idx === 0;
                      const styleClass = getStyleClass(event.signalAction);
                      const count = getExtremeCount(event);

                      return (
                        <div key={idx} className="op-timeline-item">
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
                                {event.evaluatedAt ? new Date(event.evaluatedAt).toLocaleTimeString() : '--:--:--'}
                              </span>
                            </div>

                            <div className="op-timeline-data">
                              <span>LTP: <span className="op-text-white">{event.ltp != null ? event.ltp.toFixed(2) : '0.00'}</span></span>
                              <span>RSI: <span className="op-text-white">{event.currentRsi != null ? event.currentRsi.toFixed(1) : '--'}</span></span>
                              <span>MA: <span className="op-text-white">{event.currentMa != null ? event.currentMa.toFixed(2) : '--'}</span></span>

                              {count !== null && (
                                <span>Extreme Count: <span className="op-text-white">{count}</span></span>
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