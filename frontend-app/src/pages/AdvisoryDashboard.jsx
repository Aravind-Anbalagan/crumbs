import React, { useState, useEffect, useMemo } from 'react';
import './AdvisoryDashboard.css';

export default function AdvisoryDashboard() {
    const [activeRecords, setActiveRecords] = useState([]);
    const [history, setHistory] = useState([]);
    const [selectedSymbol, setSelectedSymbol] = useState(null);

    // UI States
    const [loading, setLoading] = useState(true);
    const [scanningAll, setScanningAll] = useState(false);
    const [triggeringSymbol, setTriggeringSymbol] = useState(null);

    // Filter States
    const [statusFilter, setStatusFilter] = useState('ALL');
    const [actionFilter, setActionFilter] = useState('ALL');

    useEffect(() => {
        fetchDashboard();
    }, []);

    const fetchDashboard = () => {
        setLoading(true);
        fetch('/api/v1/advisory/dashboard')
            .then(res => res.status === 204 ? [] : res.json())
            .then(data => setActiveRecords(Array.isArray(data) ? data : []))
            .catch(err => console.error(err))
            .finally(() => setLoading(false));
    };

    const handleScanActive = () => {
        setScanningAll(true);
        fetch('/api/v1/advisory/scan-active', { method: 'POST' })
            .then(() => fetchDashboard())
            .finally(() => setScanningAll(false));
    };

    const handleTriggerSingle = (symbol) => {
        setTriggeringSymbol(symbol);
        fetch(`/api/v1/advisory/trigger/${symbol}`, { method: 'POST' })
            .then(() => fetchDashboard())
            .finally(() => setTriggeringSymbol(null));
    };

    const handleViewHistory = (symbol) => {
        setSelectedSymbol(symbol);
        fetch(`/api/v1/advisory/history/${symbol}`)
            .then(res => res.status === 204 ? [] : res.json())
            .then(data => setHistory(Array.isArray(data) ? data : []))
            .catch(err => console.error(err));
    };

    const formatPnL = (pnl) => {
        if (pnl === null || pnl === undefined) return <span className="text-muted">-</span>;
        const val = parseFloat(pnl);
        if (val > 0) return <span className="pnl-positive">+₹{val.toFixed(2)}</span>;
        if (val < 0) return <span className="pnl-negative">-₹{Math.abs(val).toFixed(2)}</span>;
        return <span className="pnl-neutral">₹0.00</span>;
    };

    // Extract unique labels for our Filter Chips
    const uniqueStatuses = useMemo(() => {
        const statuses = activeRecords.map(r => r.status).filter(Boolean);
        return [...new Set(statuses)];
    }, [activeRecords]);

    const uniqueActions = useMemo(() => {
        const actions = activeRecords.map(r => r.actionTaken).filter(Boolean);
        return [...new Set(actions)];
    }, [activeRecords]);

    // Apply Filters
    const filteredRecords = useMemo(() => {
        return activeRecords.filter(record => {
            const matchStatus = statusFilter === 'ALL' || record.status === statusFilter;
            const matchAction = actionFilter === 'ALL' || record.actionTaken === actionFilter;
            return matchStatus && matchAction;
        });
    }, [activeRecords, statusFilter, actionFilter]);

    return (
        <div className="advisory-container">

            {/* 🚀 NEW: UNIFIED COMMAND BAR */}
            <div className="advisory-header glass-panel">

                {/* Left: Title Area */}
                <div className="header-title-area">
                    <h2>Advisory Engine Central</h2>
                    <span className="subtitle">
                        Showing {filteredRecords.length} of {activeRecords.length} Signals
                    </span>
                </div>

                {/* Center: Inline Filter Chips */}
                <div className="header-filters-area">
                    <div className="filter-inline-group">
                        <span className="filter-label">Status</span>
                        <button
                            className={`filter-chip ${statusFilter === 'ALL' ? 'active' : ''}`}
                            onClick={() => setStatusFilter('ALL')}
                        >All</button>
                        {uniqueStatuses.map(status => (
                            <button
                                key={status}
                                className={`filter-chip ${statusFilter === status ? 'active' : ''}`}
                                onClick={() => setStatusFilter(status)}
                            >{status}</button>
                        ))}
                    </div>

                    <div className="filter-inline-group">
                        <span className="filter-label">Action</span>
                        <button
                            className={`filter-chip ${actionFilter === 'ALL' ? 'active' : ''}`}
                            onClick={() => setActionFilter('ALL')}
                        >All</button>
                        {uniqueActions.map(action => (
                            <button
                                key={action}
                                className={`filter-chip ${actionFilter === action ? 'active' : ''}`}
                                onClick={() => setActionFilter(action)}
                            >{action.replace(/_/g, ' ')}</button>
                        ))}
                    </div>
                </div>

                {/* Right: Actions */}
                <div className="header-actions-area">
                    {(statusFilter !== 'ALL' || actionFilter !== 'ALL') && (
                        <button
                            className="btn-reset"
                            onClick={() => { setStatusFilter('ALL'); setActionFilter('ALL'); }}
                        >
                            ✖ Reset Filters
                        </button>
                    )}
                    <button className="btn-primary" onClick={handleScanActive} disabled={scanningAll}>
                        {scanningAll ? '⏳ Scanning Engine...' : '⚡ Scan All Active'}
                    </button>
                </div>

            </div>

            {/* MAIN CONTENT AREA */}
            <div className="advisory-content">
                <div className="advisory-grid-wrapper">
                    {loading ? (
                        <div className="loading-spinner">Loading Active Recommendations...</div>
                    ) : filteredRecords.length === 0 ? (
                        <div className="empty-state glass-panel">No signals match your filters.</div>
                    ) : (
                        <div className="advisory-grid">
                            {filteredRecords.map((record) => (
                                <div key={record.id || record.symbol} className="advisory-card glass-panel">

                                    <div className="card-header">
                                        <h3 className="symbol-title" onClick={() => handleViewHistory(record.symbol)}>
                                            {record.symbol} 🔍
                                        </h3>
                                        <span className={`status-badge ${record.status?.toLowerCase()}`}>
                                            {record.status}
                                        </span>
                                    </div>

                                    <div className="card-sub-header">
                                        <span className="action-tag">{record.actionTaken || 'N/A'}</span>
                                        {record.dailyTrend && (
                                            <span className={`trend-tag ${record.dailyTrend.toLowerCase()}`}>
                                                {record.dailyTrend}
                                            </span>
                                        )}
                                    </div>

                                    <hr className="card-divider" />

                                    <div className="card-metrics">
                                        <div className="metric-box">
                                            <span className="metric-label">Position</span>
                                            <span className="metric-value highlight">
                                                {record.recommendedStrike ? `${record.recommendedStrike} ${record.optionType}` : '-'}
                                            </span>
                                        </div>
                                        <div className="metric-box">
                                            <span className="metric-label">Premium (In/Out)</span>
                                            <span className="metric-value">
                                                {record.entryPremium ? `₹${parseFloat(record.entryPremium).toFixed(1)}` : '-'} / {record.exitPremium ? `₹${parseFloat(record.exitPremium).toFixed(1)}` : 'Live'}
                                            </span>
                                        </div>
                                        <div className="metric-box">
                                            <span className="metric-label">Floating PnL</span>
                                            <span className="metric-value">{formatPnL(record.unrealizedPnl)}</span>
                                        </div>
                                        <div className="metric-box">
                                            <span className="metric-label">Realized PnL</span>
                                            <span className="metric-value">{formatPnL(record.realizedPnl)}</span>
                                        </div>
                                    </div>

                                    <hr className="card-divider" />

                                    <div className="card-footer">
                                        <div className="footer-details">
                                            <span className="expiry-text">Exp: {record.expiryDate || '-'}</span>
                                                                                        <span className="timestamp-text">
                                                                                            Updated: {record.timestamp
                                                                                                ? new Date(record.timestamp).toLocaleString([], {
                                                                                                    month: 'short',
                                                                                                    day: 'numeric',
                                                                                                    hour: '2-digit',
                                                                                                    minute: '2-digit'
                                                                                                })
                                                                                                : '-'}
                                                                                        </span>
                                        </div>
                                        <button
                                            className="btn-action-small"
                                            onClick={() => handleTriggerSingle(record.symbol)}
                                            disabled={triggeringSymbol === record.symbol}
                                        >
                                            {triggeringSymbol === record.symbol ? '⚙️...' : 'Force Run'}
                                        </button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>

                {/* DRILL-DOWN HISTORY PANEL */}
                {selectedSymbol && (
                    <div className="advisory-history-panel glass-panel">
                        <div className="history-header">
                            <h3>{selectedSymbol} Audit Trail</h3>
                            <button className="btn-close" onClick={() => setSelectedSymbol(null)}>✖</button>
                        </div>
                        <div className="history-timeline">
                            {history.length === 0 ? (
                                <p className="text-muted">No history found for {selectedSymbol}.</p>
                            ) : (
                                history.map((histRow) => (
                                    <div key={histRow.id || Math.random()} className="timeline-item">
                                        <div className="timeline-date">
                                            {histRow.timestamp ? new Date(histRow.timestamp).toLocaleString() : 'N/A'}
                                        </div>
                                        <div className="timeline-content">
                                            <strong>{histRow.actionTaken}
                                                {histRow.recommendedStrike && ` (${histRow.recommendedStrike} ${histRow.optionType})`}
                                            </strong>
                                            {histRow.realizedPnl != null && (
                                                <div className="history-pnl">
                                                    Result: {formatPnL(histRow.realizedPnl)}
                                                </div>
                                            )}
                                            <p>{histRow.reasoning}</p>
                                        </div>
                                    </div>
                                ))
                            )}
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}