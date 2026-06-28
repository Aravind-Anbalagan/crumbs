import React, { useState, useEffect } from 'react';

const OrderHistory = () => {
  const [trades, setTrades] = useState([]);
  const [loading, setLoading] = useState(true);
  
  const [filterMonth, setFilterMonth] = useState('ALL');
  // activeView controls the modal popup now
  const [activeView, setActiveView] = useState(null); 

  useEffect(() => {
    fetch('/api/v1/history/overall')
      .then(response => {
        if (!response.ok) throw new Error('Network response was not ok');
        return response.json();
      })
      .then(data => {
        setTrades(data);
        setLoading(false);
      })
      .catch(error => {
        console.error("Error fetching history:", error);
        setLoading(false);
      });
  }, []);

  // --- CORE DATA PREP ---
  const uniqueMonths = [...new Set(trades.map(t => t.tradeMonth))].sort().reverse();
  const tradesByMonth = trades.filter(t => filterMonth === 'ALL' || t.tradeMonth === filterMonth);
  const uniqueStrategies = [...new Set(tradesByMonth.map(t => t.signal))].filter(Boolean);

  // --- KPI CALCULATION ENGINE ---
  const calculateKPIs = (tradeList) => {
    if (!tradeList || tradeList.length === 0) {
      return { count: 0, winRate: 0, maxProfit: 0, maxLoss: 0, totalPnl: 0 };
    }
    const count = tradeList.length;
    const winningTrades = tradeList.filter(t => t.realizedPnl > 0).length;
    const winRate = ((winningTrades / count) * 100).toFixed(1);
    const totalPnl = tradeList.reduce((sum, t) => sum + t.realizedPnl, 0);
    const maxProfit = Math.max(...tradeList.map(t => t.realizedPnl), 0);
    const maxLoss = Math.min(...tradeList.map(t => t.realizedPnl), 0);

    return { count, winRate, maxProfit, maxLoss, totalPnl };
  };

  const statsOverall = calculateKPIs(tradesByMonth);
  const statsLive = calculateKPIs(tradesByMonth.filter(t => t.executionType === 'LIVE'));
  const statsPaper = calculateKPIs(tradesByMonth.filter(t => t.executionType === 'PAPER'));

  // --- TABLE DATA DERIVATION FOR MODAL ---
  let tableData = [];
  if (activeView) {
    if (activeView.type === 'OVERALL') tableData = tradesByMonth;
    else if (activeView.type === 'MODE') tableData = tradesByMonth.filter(t => t.executionType === activeView.value);
    else if (activeView.type === 'STRATEGY') tableData = tradesByMonth.filter(t => t.signal === activeView.value);
  }

  // --- REUSABLE TILE COMPONENT ---
  const StatTile = ({ title, stats, type, value, icon }) => {
    const pnlColor = stats.totalPnl >= 0 ? 'text-success' : 'text-danger';
    
    return (
      <div 
        className="interactive-tile"
        onClick={() => setActiveView({ type, value, title })}
      >
        <div className="tile-header">
          <span className="tile-title">{icon} {title}</span>
          <span className={`tile-main-pnl ${pnlColor}`}>
            ₹{stats.totalPnl.toLocaleString('en-IN', { minimumFractionDigits: 2 })}
          </span>
        </div>
        
        <div className="tile-metrics-grid">
          <div className="metric-box">
            <span className="metric-label">Trades</span>
            <span className="metric-val">{stats.count}</span>
          </div>
          <div className="metric-box">
            <span className="metric-label">Win Rate</span>
            <span className="metric-val">{stats.winRate}%</span>
          </div>
          <div className="metric-box">
            <span className="metric-label">Max Profit</span>
            <span className="metric-val text-success">₹{stats.maxProfit.toFixed(0)}</span>
          </div>
          <div className="metric-box">
            <span className="metric-label">Max Loss</span>
            <span className="metric-val text-danger">₹{stats.maxLoss.toFixed(0)}</span>
          </div>
        </div>
      </div>
    );
  };

  if (loading) return <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-muted)' }}>Loading Crumbs Analytics...</div>;

  return (
    <>
      <style>{`
        .analytics-container { padding: 2rem; width: 100%; overflow-y: auto; height: 100%; }
        
        .header-section { display: flex; justify-content: space-between; align-items: center; margin-bottom: 2rem; }
        .page-title { font-size: 1.8rem; font-weight: bold; color: var(--text-main); margin: 0; }
        .filter-select { background: var(--glass-bg); color: var(--text-main); border: 1px solid var(--glass-border); padding: 8px 16px; border-radius: 8px; font-weight: 600; outline: none; cursor: pointer; backdrop-filter: blur(12px); }
        .filter-select option { background: var(--bg-main); color: var(--text-main); }
        .section-title { font-size: 1.1rem; font-weight: 600; color: var(--text-muted); margin-bottom: 1rem; border-bottom: 1px solid var(--glass-border); padding-bottom: 0.5rem; }

        .tile-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1.5rem; margin-bottom: 2.5rem; }
        .interactive-tile { background: var(--glass-bg); border: 1px solid var(--glass-border); border-radius: 12px; padding: 1.5rem; backdrop-filter: blur(12px); cursor: pointer; transition: all 0.2s ease; position: relative; overflow: hidden; }
        .interactive-tile:hover { transform: translateY(-3px); box-shadow: 0 8px 15px rgba(0,0,0,0.1); border-color: var(--nav-active); }
        
        .tile-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 1.2rem; }
        .tile-title { font-size: 1.1rem; font-weight: 700; color: var(--text-main); display: flex; align-items: center; gap: 8px; }
        .tile-main-pnl { font-size: 1.4rem; font-weight: 800; }
        
        .tile-metrics-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
        .metric-box { display: flex; flex-direction: column; background: rgba(0,0,0,0.1); padding: 8px 12px; border-radius: 6px; }
        .theme-light .metric-box { background: rgba(0,0,0,0.03); }
        .metric-label { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.5px; color: var(--text-muted); margin-bottom: 4px; }
        .metric-val { font-size: 1.05rem; font-weight: 700; color: var(--text-main); }
        
        .text-success { color: #10b981 !important; }
        .text-danger { color: #ef4444 !important; }

        /* --- RICH POPUP (MODAL) STYLES --- */
        .modal-backdrop {
          position: fixed; inset: 0; z-index: 999;
          background: rgba(0, 0, 0, 0.6);
          backdrop-filter: blur(6px);
          display: flex; justify-content: center; align-items: center;
          padding: 2rem;
          animation: fadeIn 0.2s ease-out;
        }
        .modal-container {
          background: var(--bg-main);
          border: 1px solid var(--glass-border);
          border-radius: 12px;
          width: 100%; max-width: 1100px; max-height: 90vh;
          display: flex; flex-direction: column;
          box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
          overflow: hidden;
        }
        .modal-header-bar {
          display: flex; justify-content: space-between; align-items: center;
          padding: 1.5rem;
          border-bottom: 1px solid var(--glass-border);
          background: var(--glass-bg);
        }
        .modal-title-text { font-size: 1.3rem; font-weight: bold; color: var(--text-main); margin: 0; }
        .close-btn {
          background: transparent; border: none; font-size: 1.8rem; line-height: 1;
          color: var(--text-muted); cursor: pointer; transition: color 0.2s;
        }
        .close-btn:hover { color: var(--danger-text); }
        .modal-body {
          padding: 1.5rem; overflow-y: auto; background: var(--bg-main);
        }

        .table-wrapper { border: 1px solid var(--glass-border); border-radius: 8px; overflow-x: auto; }
        .history-table { width: 100%; border-collapse: collapse; text-align: left; }
        .history-table th { padding: 1rem; border-bottom: 1px solid var(--glass-border); color: var(--text-muted); font-size: 0.85rem; text-transform: uppercase; background: var(--glass-bg); position: sticky; top: 0; }
        .history-table td { padding: 1rem; border-bottom: 1px solid var(--glass-border); color: var(--text-main); font-size: 0.95rem; }
        .history-table tbody tr:hover { background: rgba(255, 255, 255, 0.03); }
        .sub-text { font-size: 0.75rem; color: var(--text-muted); margin-top: 2px; }
        
        .tag { padding: 4px 10px; border-radius: 6px; font-size: 0.75rem; font-weight: 700; }
        .tag-live { background: rgba(14, 165, 233, 0.15); color: #0ea5e9; border: 1px solid rgba(14, 165, 233, 0.3); }
        .tag-paper { background: rgba(245, 158, 11, 0.15); color: #f59e0b; border: 1px solid rgba(245, 158, 11, 0.3); }
        
        @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
      `}</style>

      <div className="analytics-container">
        
        <div className="header-section">
          <h1 className="page-title">Performance Hub</h1>
          <select className="filter-select" value={filterMonth} onChange={(e) => { setFilterMonth(e.target.value); setActiveView(null); }}>
            <option value="ALL">All Months History</option>
            {uniqueMonths.map(month => <option key={month} value={month}>{month}</option>)}
          </select>
        </div>

        <h2 className="section-title">Execution Modes</h2>
        <div className="tile-grid">
          <StatTile title="Overall (Live + Paper)" icon="📊" stats={statsOverall} type="OVERALL" value="ALL" />
          <StatTile title="Live Trading" icon="🔥" stats={statsLive} type="MODE" value="LIVE" />
          <StatTile title="Paper Trading" icon="📄" stats={statsPaper} type="MODE" value="PAPER" />
        </div>

        {uniqueStrategies.length > 0 && (
          <>
            <h2 className="section-title">Strategy Performance</h2>
            <div className="tile-grid">
              {uniqueStrategies.map(strategy => {
                const stratStats = calculateKPIs(tradesByMonth.filter(t => t.signal === strategy));
                return <StatTile key={strategy} title={strategy} icon="⚙️" stats={stratStats} type="STRATEGY" value={strategy} />;
              })}
            </div>
          </>
        )}

      </div>

      {/* --- RICH MODAL POPUP --- */}
      {activeView && (
        <div className="modal-backdrop" onClick={() => setActiveView(null)}>
          {/* Stop propagation so clicking inside the modal doesn't close it */}
          <div className="modal-container" onClick={(e) => e.stopPropagation()}>
            
            <div className="modal-header-bar">
              <h3 className="modal-title-text">Trade Log: {activeView.title}</h3>
              <button className="close-btn" onClick={() => setActiveView(null)}>&times;</button>
            </div>
            
            <div className="modal-body">
              <div className="table-wrapper">
                <table className="history-table">
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Asset</th>
                      <th>Strategy</th>
                      <th>Type</th>
                      <th>Entry</th>
                      <th>Exit</th>
                      <th style={{ textAlign: 'right' }}>PnL (₹)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tableData.map((trade) => (
                      <tr key={trade.id}>
                        <td>{trade.tradeDate}</td>
                        <td>
                          <strong>{trade.symbol}</strong>
                          <div className="sub-text">{trade.optionType} • {trade.side}</div>
                        </td>
                        <td>{trade.signal}</td>
                        <td>
                          <span className={`tag ${trade.executionType === 'LIVE' ? 'tag-live' : 'tag-paper'}`}>
                            {trade.executionType}
                          </span>
                        </td>
                        <td>
                          {trade.entryPrice.toFixed(2)}
                          <div className="sub-text">
                            {new Date(trade.entryTime).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}
                          </div>
                        </td>
                        <td>
                          {trade.exitPrice.toFixed(2)}
                          <div className="sub-text">
                            {new Date(trade.exitTime).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}
                          </div>
                        </td>
                        <td style={{ textAlign: 'right', fontWeight: 'bold' }} className={trade.realizedPnl >= 0 ? 'text-success' : 'text-danger'}>
                          {trade.realizedPnl > 0 ? '+' : ''}{trade.realizedPnl.toFixed(2)}
                        </td>
                      </tr>
                    ))}
                    {tableData.length === 0 && (
                      <tr><td colSpan="7" style={{ textAlign: 'center', padding: '3rem', color: 'var(--text-muted)' }}>No trades match this view.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

          </div>
        </div>
      )}
    </>
  );
};

export default OrderHistory;