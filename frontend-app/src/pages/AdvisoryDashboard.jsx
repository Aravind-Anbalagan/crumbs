import React, { useState, useEffect, useMemo } from 'react';
import './AdvisoryDashboard.css';

export default function AdvisoryDashboard() {
    const [timelineData, setTimelineData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [scanningAll, setScanningAll] = useState(false);
    const [dialogData, setDialogData] = useState(null);

    const today = new Date();

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

    // 🚀 NEW: Dynamic NSE Expiry Cycle Calculator
    const { startDate, endDate, daysArray, cycleRangeText } = useMemo(() => {
        const getLastThursday = (year, month) => {
            let d = new Date(year, month + 1, 0); // Last day of the month
            while (d.getDay() !== 4) d.setDate(d.getDate() - 1); // 4 = Thursday
            d.setHours(23, 59, 59, 999);
            return d;
        };

        const now = new Date();
        const currentExpiry = getLastThursday(now.getFullYear(), now.getMonth());

        let start, end;
        if (now.getTime() > currentExpiry.getTime()) {
            // We passed this month's expiry -> We are in NEXT month's cycle
            start = new Date(currentExpiry);
            start.setDate(start.getDate() + 1);
            start.setHours(0, 0, 0, 0);
            end = getLastThursday(now.getFullYear(), now.getMonth() + 1);
        } else {
            // We are in CURRENT month's cycle
            const prevExpiry = getLastThursday(now.getFullYear(), now.getMonth() - 1);
            start = new Date(prevExpiry);
            start.setDate(start.getDate() + 1);
            start.setHours(0, 0, 0, 0);
            end = currentExpiry;
        }

        // Build the columns array
        const days = [];
        let curr = new Date(start);
        while (curr <= end) {
            const dateString = `${curr.getFullYear()}-${String(curr.getMonth()+1).padStart(2,'0')}-${String(curr.getDate()).padStart(2,'0')}`;
            days.push({
                dateString,
                dayNum: curr.getDate(),
                monthStr: curr.toLocaleString('default', { month: 'short' }),
                isToday: curr.toDateString() === now.toDateString()
            });
            curr.setDate(curr.getDate() + 1);
        }

        const rangeText = `${start.getDate()} ${start.toLocaleString('default', { month: 'short' })} — ${end.getDate()} ${end.toLocaleString('default', { month: 'short' })}`;

        return { startDate: start, endDate: end, daysArray: days, cycleRangeText: rangeText };
    }, []);

    // 🧠 DYNAMIC MATRIX LOGIC: Walks timeline from dawn of time to retain "Carry-over" trades
    const symbolMatrix = useMemo(() => {
        const matrix = {};

        // Group ALL records by symbol and date
        timelineData.forEach(record => {
            if (!record.timestamp) return;
            const date = new Date(record.timestamp);
            const dateString = `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;

            if (!matrix[record.symbol]) matrix[record.symbol] = { records: {} };
            matrix[record.symbol].records[dateString] = record;
        });

        // Find the absolute earliest trade in DB to start tracking holding states
        let globalMinDate = timelineData.length > 0
            ? new Date(Math.min(...timelineData.map(r => new Date(r.timestamp))))
            : startDate;
        if (globalMinDate > startDate) globalMinDate = startDate;

        const formattedMatrix = [];

        for (const [symbol, data] of Object.entries(matrix)) {
            const rowDays = {};
            let isHolding = false;
            let currentTrade = null;
            let currentTrend = 'NEUTRAL';

            let curr = new Date(globalMinDate);
            curr.setHours(0,0,0,0);

            // Walk forward day-by-day
            while(curr <= endDate) {
                const dateString = `${curr.getFullYear()}-${String(curr.getMonth()+1).padStart(2,'0')}-${String(curr.getDate()).padStart(2,'0')}`;
                const record = data.records[dateString];
                let typeForDay = 'EMPTY';

                if (record) {
                    const action = record.actionTaken || '';
                    if (action === 'NEW_ENTRY') {
                        isHolding = true;
                        currentTrade = record;
                        currentTrend = record.optionType === 'PE' ? 'BULLISH' : 'BEARISH';
                        typeForDay = `ENTRY_${currentTrend}`;
                    }
                    else if (action.includes('EXIT') || action.includes('REVERSE')) {
                        isHolding = false;
                        typeForDay = 'EXIT';
                        currentTrend = 'NEUTRAL';
                    }
                    else {
                        typeForDay = `ACTIVE_${currentTrend}`;
                    }
                } else {
                    if (isHolding && curr <= today) {
                        typeForDay = `ACTIVE_${currentTrend}`;
                    }
                }

                // 🚀 ONLY attach to grid if it belongs to the CURRENT Expiry Cycle
                if (curr >= startDate) {
                     rowDays[dateString] = { type: typeForDay, record: record || currentTrade };
                }

                curr.setDate(curr.getDate() + 1);
            }
            formattedMatrix.push({ symbol, days: rowDays, latestTrade: currentTrade });
        }

        return formattedMatrix;
    }, [timelineData, startDate, endDate, daysArray, today]);

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
                    <span className="subtitle">Expiry Cycle: <strong>{cycleRangeText}</strong></span>
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
                            {/* Dynamic Columns based on Cycle length */}
                            <div className="timeline-days-grid" style={{ gridTemplateColumns: `repeat(${daysArray.length}, minmax(25px, 1fr))` }}>
                                {daysArray.map((dayObj) => (
                                    <div key={dayObj.dateString} className={`day-header ${dayObj.isToday ? 'is-today' : ''}`}>
                                        <span className="day-num">{dayObj.dayNum}</span>
                                        <span className="day-month">{dayObj.monthStr}</span>
                                    </div>
                                ))}
                            </div>
                        </div>

                        {symbolMatrix.map((row) => (
                            <div key={row.symbol} className="timeline-data-row">
                                <div className="timeline-symbol-col">
                                    <span className="symbol-name">{row.symbol}</span>
                                </div>
                                <div className="timeline-days-grid" style={{ gridTemplateColumns: `repeat(${daysArray.length}, minmax(25px, 1fr))` }}>
                                    {daysArray.map(dayObj => {
                                        const cell = row.days[dayObj.dateString];
                                        if (!cell) return null;
                                        const type = cell.type;

                                        return (
                                            <div
                                                key={dayObj.dateString}
                                                className={`day-cell ${type.toLowerCase()}`}
                                                onClick={() => cell.record && setDialogData({ ...cell.record, formattedDate: `${dayObj.dayNum} ${dayObj.monthStr}` })}
                                            >
                                                {type === 'ENTRY_BULLISH' && <span className="marker-icon">▲</span>}
                                                {type === 'ENTRY_BEARISH' && <span className="marker-icon">▼</span>}
                                                {type === 'EXIT' && <span className="marker-icon">✕</span>}

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

            {/* FULL SCREEN DIALOG MODAL */}
            {dialogData && (
                <div className="dialog-overlay" onClick={() => setDialogData(null)}>
                    <div className="dialog-box glass-panel" onClick={(e) => e.stopPropagation()}>
                        <div className="dialog-header">
                            <h3>{dialogData.symbol} <span className="text-muted">| {dialogData.formattedDate}</span></h3>
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