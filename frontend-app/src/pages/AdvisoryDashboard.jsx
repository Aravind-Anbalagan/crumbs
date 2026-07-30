import React, { useState, useEffect } from 'react';
import './AdvisoryDashboard.css';

export default function AdvisoryDashboard() {
    const [activeRecords, setActiveRecords] = useState([]);
    const [history, setHistory] = useState([]);
    const [selectedSymbol, setSelectedSymbol] = useState(null);

    // Loading States
    const [loading, setLoading] = useState(true);
    const [scanningAll, setScanningAll] = useState(false);
    const [triggeringSymbol, setTriggeringSymbol] = useState(null);

    // 1. Initial Load: Fetch Active Dashboard
    useEffect(() => {
        fetchDashboard();
    }, []);

    const fetchDashboard = () => {
        setLoading(true);
        fetch('/api/v1/advisory/dashboard')
            .then(res => {
                if (res.status === 204) return [];
                if (!res.ok) throw new Error('Network response was not ok');
                return res.json();
            })
            .then(data => {
                setActiveRecords(Array.isArray(data) ? data : []);
            })
            .catch(err => {
                console.error("Error fetching advisory dashboard:", err);
                setActiveRecords([]);
            })
            .finally(() => setLoading(false));
    };

    // 2. Admin Command: Scan All Active Stocks
    const handleScanActive = () => {
        setScanningAll(true);
        fetch('/api/v1/advisory/scan-active', { method: 'POST' })
            .then(res => {
                if (res.status === 204) return [];
                return res.json();
            })
            .then(data => {
                console.log("Full scan complete:", data);
                fetchDashboard(); // Refresh the table with new data
            })
            .catch(err => console.error("Scan failed:", err))
            .finally(() => setScanningAll(false));
    };

    // 3. Admin Command: Trigger Single Symbol
    const handleTriggerSingle = (symbol) => {
        setTriggeringSymbol(symbol);
        fetch(`/api/v1/advisory/trigger/${symbol}`, { method: 'POST' })
            .then(res => {
                if (res.status === 204) return null;
                return res.json();
            })
            .then(data => {
                console.log(`Triggered ${symbol}:`, data);
                fetchDashboard(); // Refresh to show updated status
            })
            .catch(err => console.error(`Trigger failed for ${symbol}:`, err))
            .finally(() => setTriggeringSymbol(null));
    };

    // 4. Drill-Down: View History Timeline
    const handleViewHistory = (symbol) => {
        setSelectedSymbol(symbol);
        fetch(`/api/v1/advisory/history/${symbol}`)
            .then(res => {
                if (res.status === 204) return [];
                return res.json();
            })
            .then(data => {
                setHistory(Array.isArray(data) ? data : []);
            })
            .catch(err => {
                console.error(`History fetch failed for ${symbol}:`, err);
                setHistory([]);
            });
    };

    return (
        <div className="advisory-container">
            {/* TOP COMMAND BAR */}
            <div className="advisory-header">
                <h2>Advisory Engine Central</h2>
                <button
                    className="btn-primary"
                    onClick={handleScanActive}
                    disabled={scanningAll}
                >
                    {scanningAll ? '⏳ Scanning Engine...' : '⚡ Scan All Active'}
                </button>
            </div>

            {/* MAIN GLOBAL DASHBOARD */}
            <div className="advisory-content">
                <div className="advisory-table-wrapper glass-panel">
                    {loading ? (
                        <p className="loading-text">Loading Active Recommendations...</p>
                    ) : (
                        <div className="table-responsive">
                            <table className="advisory-table">
                                <thead>
                                    <tr>
                                        <th>Symbol</th>
                                        <th>Status</th>
                                        <th>Action</th>
                                        <th>Position (Strike/Type)</th>
                                        <th>Premium</th>
                                        <th>Expiry</th>
                                        <th>Last Evaluated</th>
                                        <th>Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {activeRecords.length === 0 ? (
                                        <tr><td colSpan="8" className="text-center">No active signals found.</td></tr>
                                    ) : (
                                        activeRecords.map((record) => (
                                            <tr key={record.id || record.symbol}>
                                                <td className="symbol-cell" onClick={() => handleViewHistory(record.symbol)}>
                                                    {record.symbol} 🔍
                                                </td>
                                                <td>
                                                    <span className={`status-badge ${record.status?.toLowerCase()}`}>
                                                        {record.status}
                                                    </span>
                                                </td>
                                                <td><strong>{record.actionTaken || 'N/A'}</strong></td>

                                                {/* 🚀 NEW: Strike & Option Type */}
                                                <td>
                                                    {record.recommendedStrike ?
                                                        `${record.recommendedStrike} ${record.optionType}` :
                                                        <span className="text-muted">-</span>
                                                    }
                                                </td>

                                                {/* 🚀 NEW: Entry Premium */}
                                                <td>
                                                    {record.entryPremium ?
                                                        `₹${parseFloat(record.entryPremium).toFixed(2)}` :
                                                        <span className="text-muted">-</span>
                                                    }
                                                </td>

                                                {/* 🚀 NEW: Expiry Date */}
                                                <td>
                                                    {record.expiryDate ?
                                                        <span className="expiry-text">{record.expiryDate}</span> :
                                                        <span className="text-muted">-</span>
                                                    }
                                                </td>

                                                {/* 🚀 Last Updated (Timestamp) */}
                                                <td className="timestamp-text">
                                                    {record.timestamp ? new Date(record.timestamp).toLocaleString([], {
                                                        year: 'numeric', month: 'short', day: 'numeric',
                                                        hour: '2-digit', minute: '2-digit'
                                                    }) : 'N/A'}
                                                </td>

                                                <td>
                                                    <button
                                                        className="btn-action"
                                                        onClick={() => handleTriggerSingle(record.symbol)}
                                                        disabled={triggeringSymbol === record.symbol}
                                                    >
                                                        {triggeringSymbol === record.symbol ? '⚙️...' : 'Force Run'}
                                                    </button>
                                                </td>
                                            </tr>
                                        ))
                                    )}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>

                {/* DRILL-DOWN HISTORY SIDE PANEL */}
                {selectedSymbol && (
                    <div className="advisory-history-panel glass-panel">
                        <div className="history-header">
                            <h3>{selectedSymbol} Audit Trail</h3>
                            <button className="btn-close" onClick={() => setSelectedSymbol(null)}>✖</button>
                        </div>
                        <div className="history-timeline">
                            {history.length === 0 ? (
                                <p>No history found for {selectedSymbol}.</p>
                            ) : (
                                history.map((histRow) => (
                                    <div key={histRow.id || Math.random()} className="timeline-item">
                                        <div className="timeline-date">
                                            {histRow.timestamp ? new Date(histRow.timestamp).toLocaleString([], {
                                                year: 'numeric', month: 'short', day: 'numeric',
                                                hour: '2-digit', minute: '2-digit', second: '2-digit'
                                            }) : 'N/A'}
                                        </div>
                                        <div className="timeline-content">
                                            <strong>{histRow.actionTaken}
                                                {histRow.recommendedStrike && ` (${histRow.recommendedStrike} ${histRow.optionType})`}
                                            </strong>
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