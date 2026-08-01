import React, { useState, useEffect, useMemo } from 'react';
import './AdvisoryDashboard.css';

export default function AdvisoryDashboard() {
    const [timelineData, setTimelineData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [scanningAll, setScanningAll] = useState(false);

    // 🚀 NEW: State for the Clickable Dialog Modal
    const [dialogData, setDialogData] = useState(null);

    const today = new Date();
    const currentMonth = today.toLocaleString('default', { month: 'long' });
    const currentDay = today.getDate();

    useEffect(() => {
        fetchTimeline();
    }, []);

    const fetchTimeline = () => {
        setLoading(true);
        fetch('/api/v1/advisory/timeline')
            .then(res => {
                if (res.status === 204) return [];
                return res.json();
            })
            .then(data => setTimelineData(Array.isArray(data) ? data : []))
            .catch(err => console.error("Timeline fetch error:", err))
            .finally(() => setLoading(false));
    };

    const handleScanActive = () => {
        setScanningAll(true);
        fetch('/api/v1/advisory/scan-active', { method: 'POST' })
            .then(() => fetchTimeline())
            .finally(() => setScanningAll(false));
    };

    const daysInMonth = useMemo(() => {
        if (timelineData.length === 0) {
            return new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate();
        }
        const latestRecord = [...timelineData].sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp))[0];
        const latestDate = new Date(latestRecord.timestamp);
        return new Date(latestDate.getFullYear(), latestDate.getMonth() + 1, 0).getDate();
    }, [timelineData, today]);

    const daysArray = Array.from({ length: daysInMonth }, (_, i) => i + 1);

    const maxDayToDraw = useMemo(() => {
        if (timelineData.length === 0) return currentDay;
        const latestRecord = [...timelineData].sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp))[0];
        const latestDate = new Date(latestRecord.timestamp);
        if (latestDate.getMonth() === today.getMonth() && latestDate.getFullYear() === today.getFullYear()) {
            return currentDay;
        }
        return daysInMonth;
    }, [timelineData, today, currentDay, daysInMonth]);

    // 🧠 DYNAMIC MATRIX LOGIC
    const symbolMatrix = useMemo(() => {
        const matrix = {};

        timelineData.forEach(record => {
            if (!record.timestamp) return;
            const date = new Date(record.timestamp);
            const day = date.getDate();

            if (!matrix[record.symbol]) matrix[record.symbol] = { records: {} };
            matrix[record.symbol].records[day] = record;
        });

        const formattedMatrix = [];
        for (const [symbol, data] of Object.entries(matrix)) {
            const rowDays = {};
            let isHolding = false;
            let currentTrade = null;
            let currentTrend = 'NEUTRAL';

            for (let d = 1; d <= daysInMonth; d++) {
                const record = data.records[d];

                if (record) {
                    const action = record.actionTaken || '';

                    // Option Sellers: Selling PE = Bullish Market, Selling CE = Bearish Market
                    if (action === 'NEW_ENTRY') {
                        isHolding = true;
                        currentTrade = record;
                        currentTrend = record.optionType === 'PE' ? 'BULLISH' : 'BEARISH';
                        rowDays[d] = { type: `ENTRY_${currentTrend}`, record };
                    }
                    else if (action.includes('EXIT') || action.includes('REVERSE')) {
                        isHolding = false;
                        rowDays[d] = { type: 'EXIT', record };
                        currentTrend = 'NEUTRAL';
                    }
                    else {
                        rowDays[d] = { type: `ACTIVE_${currentTrend}`, record };
                    }
                } else {
                    if (isHolding && d <= maxDayToDraw) {
                        rowDays[d] = { type: `ACTIVE_${currentTrend}`, record: currentTrade };
                    } else {
                        rowDays[d] = { type: 'EMPTY' };
                    }
                }
            }
            formattedMatrix.push({ symbol, days: rowDays, latestTrade: currentTrade });
        }

        return formattedMatrix;
    }, [timelineData, daysInMonth, maxDayToDraw]);

    // Format Helper
    const formatPnL = (pnl) => {
        if (pnl === null || pnl === undefined) return '-';
        const val = parseFloat(pnl);
        if (val > 0) return <span className="text-green">+₹{val.toFixed(2)}</span>;
        if (val < 0) return <span className="text-red">-₹{Math.abs(val).toFixed(2)}</span>;
        return '₹0.00';
    };

    return (
        <div className="advisory-container">
            {/* HEADER */}
            <div className="advisory-header glass-panel">
                <div className="header-title-area">
                    <h2>Lifecycle Matrix</h2>
                    <span className="subtitle">{currentMonth} {today.getFullYear()} Trading Ribbon</span>
                </div>
                <button className="btn-primary" onClick={handleScanActive} disabled={scanningAll}>
                    {scanningAll ? '⏳ Scanning...' : '⚡ Scan Market'}
                </button>
            </div>

            {/* TIMELINE GRID */}
            <div className="timeline-wrapper glass-panel">
                {loading ? (
                    <div className="loading-spinner">Constructing Timeline Matrix...</div>
                ) : symbolMatrix.length === 0 ? (
                    <div className="empty-state">No trades found in dataset.</div>
                ) : (
                    <div className="timeline-board">
                        <div className="timeline-header-row">
                            <div className="timeline-symbol-col">Instrument</div>
                            <div className="timeline-days-grid">
                                {daysArray.map(day => (
                                    <div key={day} className={`day-header ${day === currentDay ? 'is-today' : ''}`}>
                                        {day}
                                    </div>
                                ))}
                            </div>
                        </div>

                        {symbolMatrix.map((row) => (
                            <div key={row.symbol} className="timeline-data-row">
                                <div className="timeline-symbol-col">
                                    <span className="symbol-name">{row.symbol}</span>
                                </div>
                                <div className="timeline-days-grid">
                                    {daysArray.map(day => {
                                        const cell = row.days[day];
                                        const type = cell.type;
                                        return (
                                            <div
                                                                                            key={day}
                                                                                            className={`day-cell ${type.toLowerCase()}`}
                                                                                            onClick={() => cell.record && setDialogData({ ...cell.record, day })}
                                                                                        >
                                                                                            {/* 🚀 FIXED: Strict geometric shapes to prevent OS emojis */}
                                                                                            {type === 'ENTRY_BULLISH' && <span className="marker-icon">▲</span>}
                                                                                            {type === 'ENTRY_BEARISH' && <span className="marker-icon">▼</span>}
                                                                                            {type === 'EXIT' && <span className="marker-icon">✕</span>}

                                                                                            {/* Tooltip (Fixed Z-Index via CSS) */}
                                                                                            {type !== 'EMPTY' && cell.record && (
                                                                                                <div className="day-tooltip">
                                                                                                    <strong>{cell.record.actionTaken || 'HOLDING'}</strong>
                                                                                                    <span>{cell.record.recommendedStrike} {cell.record.optionType}</span>
                                                                                                </div>
                                                                                            )}
                                                                                        </div>
                                        );
                                    })}
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </div>

            {/* 🚀 NEW: FULL SCREEN DIALOG MODAL */}
            {dialogData && (
                <div className="dialog-overlay" onClick={() => setDialogData(null)}>
                    <div className="dialog-box glass-panel" onClick={(e) => e.stopPropagation()}>
                        <div className="dialog-header">
                            <h3>{dialogData.symbol} <span className="text-muted">| Day {dialogData.day}</span></h3>
                            <button className="btn-close" onClick={() => setDialogData(null)}>✖</button>
                        </div>

                        <div className="dialog-content">
                            <div className="dialog-status-row">
                                <span className={`status-badge ${dialogData.status?.toLowerCase()}`}>{dialogData.status}</span>
                                <strong>{dialogData.actionTaken}</strong>
                            </div>

                            <div className="dialog-grid">
                                <div className="d-box">
                                    <label>Strike</label>
                                    <span>{dialogData.recommendedStrike ? `${dialogData.recommendedStrike} ${dialogData.optionType}` : 'N/A'}</span>
                                </div>
                                <div className="d-box">
                                    <label>Spot Price</label>
                                    <span>₹{dialogData.spotPrice}</span>
                                </div>
                                <div className="d-box">
                                    <label>Entry Premium</label>
                                    <span>₹{dialogData.entryPremium || '-'}</span>
                                </div>
                                <div className="d-box">
                                    <label>Exit Premium</label>
                                    <span>{dialogData.exitPremium ? `₹${dialogData.exitPremium}` : 'LIVE'}</span>
                                </div>
                                <div className="d-box">
                                    <label>Realized PnL</label>
                                    <span>{formatPnL(dialogData.realizedPnl)}</span>
                                </div>
                                <div className="d-box">
                                    <label>Daily Trend</label>
                                    <span>{dialogData.dailyTrend || '-'}</span>
                                </div>
                            </div>

                            <div className="dialog-reasoning">
                                <label>AI Reasoning & Logic</label>
                                <p>{dialogData.reasoning || 'No specific reasoning provided by the engine.'}</p>
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}