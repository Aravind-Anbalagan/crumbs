import React, { useState, useEffect, useMemo } from 'react';
import './AdvisoryDashboard.css';

const PAGE_SIZE = 10;

// Classifies a row's TODAY cell into one of 5 filter buckets (using backend states)
function getFilterCategory(todayCell) {
    if (!todayCell) return 'OTHER';
    switch (todayCell.type) {
        case 'ENTRY_BULLISH':
        case 'ENTRY_BEARISH':
            return 'NEW_ENTRY';
        case 'EXIT_SL':
            return 'EXIT_SL';
        case 'EXIT_TARGET':
            return 'EXIT_TARGET';
        case 'ACTIVE_BULLISH':
        case 'ACTIVE_BEARISH':
            return 'MAINTAIN';
        default:
            return 'NO_TRADE'; // NO_TRADE / EMPTY → unified grey
    }
}

// Maps action taken to color class (for visual distinction)
function getColorFromCellType(type) {
    if (type === 'ENTRY_BULLISH' || type === 'ENTRY_BEARISH') return 'color-new_entry';
    if (type === 'ACTIVE_BULLISH' || type === 'ACTIVE_BEARISH') return 'color-maintain';
    if (type === 'EXIT_SL') return 'color-exit-sl';
    if (type === 'EXIT_TARGET') return 'color-exit-target';
    return 'color-no_trade'; // NO_TRADE, EMPTY
}

export default function AdvisoryDashboard() {
    const [timelineData, setTimelineData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [scanningAll, setScanningAll] = useState(false);
    const [dialogData, setDialogData] = useState(null);
    const [error, setError] = useState(null);
    const [filterType, setFilterType] = useState('ALL');
    const [currentPage, setCurrentPage] = useState(1);

    const today = new Date();

    useEffect(() => {
        fetchTimeline();
    }, []);

    useEffect(() => {
        setCurrentPage(1);
    }, [filterType, timelineData]);

    useEffect(() => {
        if (!dialogData) return;
        const handleKey = (e) => {
            if (e.key === 'Escape') setDialogData(null);
        };
        window.addEventListener('keydown', handleKey);
        return () => window.removeEventListener('keydown', handleKey);
    }, [dialogData]);

    const fetchTimeline = () => {
        setLoading(true);
        setError(null);
        fetch('/api/v1/advisory/timeline')
            .then(res => {
                if (res.status === 204) return [];
                if (!res.ok) throw new Error(`Request failed with status ${res.status}`);
                return res.json();
            })
            .then(data => {
                const arrayData = Array.isArray(data) ? data : [];
                console.log(`📊 Timeline fetched: ${arrayData.length} records`);
                if (isNewCycle) {
                    console.log(`🔄 NEW CYCLE detected. Filtering to current cycle only (${cycleRangeText})`);
                }
                setTimelineData(arrayData);
            })
            .catch(err => {
                console.error("Timeline fetch error:", err);
                setError('Could not load the advisory timeline. Please refresh or try again.');
            })
            .finally(() => setLoading(false));
    };

    const handleScanActive = () => {
        setScanningAll(true);
        setError(null);
        fetch('/api/v1/advisory/scan-active', { method: 'POST' })
            .then(res => {
                if (!res.ok) throw new Error(`Scan failed with status ${res.status}`);
                return fetchTimeline();
            })
            .catch(err => {
                console.error("Scan error:", err);
                setError('Market scan failed to trigger. Please try again.');
            })
            .finally(() => setScanningAll(false));
    };

    // 🚀 Dynamic NSE Expiry Cycle Calculator (Last TUESDAY of month)
    const { startDate, endDate, daysArray, cycleRangeText, isNewCycle } = useMemo(() => {
        // NSE options expiry: Last TUESDAY of each month (not Thursday)
        const getLastTuesday = (year, month) => {
            let d = new Date(year, month + 1, 0);
            // TUESDAY = day 2 (0=Sun, 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat)
            while (d.getDay() !== 2) d.setDate(d.getDate() - 1);
            d.setHours(23, 59, 59, 999);
            return d;
        };

        const now = new Date();
        now.setHours(0, 0, 0, 0);
        const currentExpiry = getLastTuesday(now.getFullYear(), now.getMonth());

        let start, end, newCycleStarted = false;

        // 🔄 NEW CYCLE DETECTION: if today > current expiry, we're in a new cycle
        if (now.getTime() > currentExpiry.getTime()) {
            newCycleStarted = true;
            // New cycle starts from day after last expiry
            start = new Date(currentExpiry);
            start.setDate(start.getDate() + 1);
            start.setHours(0, 0, 0, 0);
            // Cycle ends on next month's last TUESDAY
            end = getLastTuesday(now.getFullYear(), now.getMonth() + 1);
        } else {
            // Still in current cycle
            // Cycle starts from day after previous month's last TUESDAY
            const prevExpiry = getLastTuesday(now.getFullYear(), now.getMonth() - 1);
            start = new Date(prevExpiry);
            start.setDate(start.getDate() + 1);
            start.setHours(0, 0, 0, 0);
            // Cycle ends on current month's last TUESDAY
            end = currentExpiry;
        }

        const days = [];
        let curr = new Date(start);
        while (curr <= end) {
            const dateString = `${curr.getFullYear()}-${String(curr.getMonth()+1).padStart(2,'0')}-${String(curr.getDate()).padStart(2,'0')}`;
            const dow = curr.getDay();
            days.push({
                dateString,
                dayNum: curr.getDate(),
                monthStr: curr.toLocaleString('default', { month: 'short' }),
                isToday: curr.toDateString() === now.toDateString(),
                isWeekend: dow === 0 || dow === 6
            });
            curr.setDate(curr.getDate() + 1);
        }

        const rangeText = `${start.getDate()} ${start.toLocaleString('default', { month: 'short' })} — ${end.getDate()} ${end.toLocaleString('default', { month: 'short' })}`;

        return { startDate: start, endDate: end, daysArray: days, cycleRangeText: rangeText, isNewCycle: newCycleStarted };
    }, []);

    // 🧠 DYNAMIC MATRIX LOGIC - Only process current cycle data
    const symbolMatrix = useMemo(() => {
        const matrix = {};

        // 🔄 FILTER: Only include trades that fall within the current cycle
        const filteredTimelineData = timelineData.filter(record => {
            if (!record.timestamp) return false;
            const recordDate = new Date(record.timestamp);
            recordDate.setHours(0, 0, 0, 0);
            // Include only if record is within [startDate, endDate]
            return recordDate >= startDate && recordDate <= endDate;
        });

        filteredTimelineData.forEach(record => {
            if (!record.timestamp) return;
            const date = new Date(record.timestamp);
            const dateString = `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;

            if (!matrix[record.symbol]) matrix[record.symbol] = { records: {} };
            matrix[record.symbol].records[dateString] = record;
        });

        const formattedMatrix = [];
        const todayDateString = `${today.getFullYear()}-${String(today.getMonth()+1).padStart(2,'0')}-${String(today.getDate()).padStart(2,'0')}`;

        for (const [symbol, data] of Object.entries(matrix)) {
            const rowDays = {};
            let isHolding = false;
            let currentTrade = null;
            let currentTrend = 'NEUTRAL';
            let todayCell = null;

            let curr = new Date(startDate);
            curr.setHours(0,0,0,0);

            while(curr <= endDate) {
                const dateString = `${curr.getFullYear()}-${String(curr.getMonth()+1).padStart(2,'0')}-${String(curr.getDate()).padStart(2,'0')}`;
                const record = data.records[dateString];
                let typeForDay = 'EMPTY';

                if (record) {
                    currentTrade = record;
                    const action = record.actionTaken || '';

                    if (action === 'NEW_ENTRY') {
                        isHolding = true;
                        currentTrend = record.optionType === 'PE' ? 'BULLISH' : 'BEARISH';
                        typeForDay = `ENTRY_${currentTrend}`;
                    }
                    else if (action === 'SL') {
                        isHolding = false;
                        typeForDay = 'EXIT_SL';
                        currentTrend = 'NEUTRAL';
                    }
                    else if (action === 'TARGET') {
                        isHolding = false;
                        typeForDay = 'EXIT_TARGET';
                        currentTrend = 'NEUTRAL';
                    }
                    else if (action === 'MAINTAIN' && isHolding) {
                        typeForDay = `ACTIVE_${currentTrend}`;
                    }
                    else {
                        typeForDay = 'NO_TRADE';
                    }
                } else {
                    if (isHolding && curr <= today) {
                        typeForDay = `ACTIVE_${currentTrend}`;
                    }
                }

                if (curr >= startDate) {
                    const dow = curr.getDay();
                    const dayCell = {
                        type: typeForDay,
                        record: record || currentTrade,
                        isWeekend: dow === 0 || dow === 6
                    };
                    rowDays[dateString] = dayCell;

                    if (dateString === todayDateString) {
                        todayCell = dayCell;
                    }
                }

                curr.setDate(curr.getDate() + 1);
            }
            formattedMatrix.push({ symbol, days: rowDays, latestTrade: currentTrade, todayCell });
        }

        return formattedMatrix;
    }, [timelineData, startDate, endDate, daysArray, today]);

    // Counts per filter bucket (group SL and TARGET under EXIT)
    const filterCounts = useMemo(() => {
        const counts = { ALL: symbolMatrix.length, NEW_ENTRY: 0, EXIT: 0, MAINTAIN: 0, NO_TRADE: 0 };
        symbolMatrix.forEach(row => {
            const cat = getFilterCategory(row.todayCell);
            if (cat === 'EXIT_SL' || cat === 'EXIT_TARGET') {
                counts['EXIT'] += 1;
            } else if (counts[cat] !== undefined) {
                counts[cat] += 1;
            }
        });
        return counts;
    }, [symbolMatrix]);

    const filteredMatrix = useMemo(() => {
        if (filterType === 'ALL') return symbolMatrix;
        if (filterType === 'EXIT') {
            return symbolMatrix.filter(row => {
                const cat = getFilterCategory(row.todayCell);
                return cat === 'EXIT_SL' || cat === 'EXIT_TARGET';
            });
        }
        return symbolMatrix.filter(row => getFilterCategory(row.todayCell) === filterType);
    }, [symbolMatrix, filterType]);

    const totalPages = Math.max(1, Math.ceil(filteredMatrix.length / PAGE_SIZE));

    const pagedMatrix = useMemo(() => {
        const start = (currentPage - 1) * PAGE_SIZE;
        return filteredMatrix.slice(start, start + PAGE_SIZE);
    }, [filteredMatrix, currentPage]);

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
                    <span className="subtitle">
                        Expiry Cycle: <strong>{cycleRangeText}</strong>
                        {isNewCycle && <span className="new-cycle-badge">🔄 NEW CYCLE</span>}
                    </span>
                </div>

                <div className="legend-row">
                    <span className="legend-item"><span className="legend-swatch color-new_entry" />New Entry</span>
                    <span className="legend-item"><span className="legend-swatch color-maintain" />Maintain</span>
                    <span className="legend-item"><span className="legend-swatch color-exit-sl" />Exit (SL)</span>
                    <span className="legend-item"><span className="legend-swatch color-exit-target" />Exit (Target)</span>
                    <span className="legend-item"><span className="legend-swatch color-no_trade" />No Trade</span>
                </div>

                <button className="btn-primary" onClick={handleScanActive} disabled={scanningAll}>
                    {scanningAll ? '⏳ Scanning...' : '⚡ Scan Market'}
                </button>
            </div>

            {/* ERROR BANNER */}
            {error && (
                <div className="error-banner glass-panel">
                    <span>⚠️ {error}</span>
                    <button onClick={() => setError(null)}>✕</button>
                </div>
            )}

            {/* FILTER BAR */}
            <div className="filter-bar glass-panel">
                <div className="filter-inline-group">
                    <span className="filter-label">Today</span>
                    {[
                        { key: 'ALL', label: 'All', count: filterCounts.ALL },
                        { key: 'NEW_ENTRY', label: 'New Entry', count: filterCounts.NEW_ENTRY },
                        { key: 'EXIT', label: 'Exit Today', count: filterCounts.EXIT },
                        { key: 'MAINTAIN', label: 'Maintain', count: filterCounts.MAINTAIN },
                    ].map(f => (
                        <button
                            key={f.key}
                            className={`filter-chip ${filterType === f.key ? 'active' : ''}`}
                            onClick={() => setFilterType(f.key)}
                        >
                            {f.label} ({f.count})
                        </button>
                    ))}
                </div>
            </div>

            {/* TIMELINE GRID */}
            <div className="timeline-wrapper glass-panel">
                {loading ? (
                    <div className="loading-spinner">Constructing Timeline Matrix...</div>
                ) : symbolMatrix.length === 0 ? (
                    <div className="empty-state">No trades found in dataset.</div>
                ) : filteredMatrix.length === 0 ? (
                    <div className="empty-state">No instruments match this filter today.</div>
                ) : (
                    <>
                        <div className="timeline-scroll-area">
                            <div className="timeline-board">

                                <div className="timeline-header-row">
                                    <div className="timeline-symbol-col">Instrument</div>
                                    <div className="timeline-days-grid" style={{ gridTemplateColumns: `repeat(${daysArray.length}, minmax(25px, 1fr))` }}>
                                        {daysArray.map((dayObj) => (
                                            <div
                                                key={dayObj.dateString}
                                                className={`day-header ${dayObj.isToday ? 'is-today' : ''} ${dayObj.isWeekend ? 'is-weekend' : ''}`}
                                            >
                                                <span className="day-num">{dayObj.dayNum}</span>
                                                <span className="day-month">{dayObj.monthStr}</span>
                                            </div>
                                        ))}
                                    </div>
                                </div>

                                {pagedMatrix.map((row) => (
                                    <div key={row.symbol} className="timeline-data-row">
                                        <div className="timeline-symbol-col">
                                            <span className="symbol-name">{row.symbol}</span>
                                        </div>
                                        <div className="timeline-days-grid" style={{ gridTemplateColumns: `repeat(${daysArray.length}, minmax(25px, 1fr))` }}>
                                            {daysArray.map(dayObj => {
                                                const cell = row.days[dayObj.dateString];
                                                if (!cell) return null;
                                                const type = cell.type;
                                                const colorClass = getColorFromCellType(type);

                                                return (
                                                    <div
                                                        key={dayObj.dateString}
                                                        className={`day-cell ${colorClass} ${cell.isWeekend ? 'is-weekend' : ''}`}
                                                        onClick={() => cell.record && setDialogData({ ...cell.record, formattedDate: `${dayObj.dayNum} ${dayObj.monthStr}` })}
                                                    >
                                                        {type === 'ENTRY_BULLISH' && <span className="marker-icon">▲</span>}
                                                        {type === 'ENTRY_BEARISH' && <span className="marker-icon">▼</span>}
                                                        {type === 'EXIT_SL' && <span className="marker-icon">✕</span>}
                                                        {type === 'EXIT_TARGET' && <span className="marker-icon">✓</span>}
                                                        {type === 'NO_TRADE' && <span className="marker-icon">·</span>}

                                                        {type !== 'EMPTY' && cell.record && (
                                                            <div className="day-tooltip">
                                                                <strong>{cell.record.actionTaken || 'HOLDING'}</strong>
                                                                {cell.record.recommendedStrike && (
                                                                    <span>{cell.record.recommendedStrike} {cell.record.optionType}</span>
                                                                )}
                                                            </div>
                                                        )}
                                                    </div>
                                                );
                                            })}
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>

                        {/* PAGINATION */}
                        <div className="pagination-bar">
                            <button
                                className="pagination-btn"
                                onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                                disabled={currentPage === 1}
                            >
                                ‹ Prev
                            </button>
                            <span className="pagination-info">
                                Page {currentPage} of {totalPages} · {filteredMatrix.length} instrument{filteredMatrix.length === 1 ? '' : 's'}
                            </span>
                            <button
                                className="pagination-btn"
                                onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                                disabled={currentPage === totalPages}
                            >
                                Next ›
                            </button>
                        </div>
                    </>
                )}
            </div>

            {/* DIALOG MODAL */}
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
                                    <label>Expiry Date</label>
                                    <span>{dialogData.expiryDate || '-'}</span>
                                </div>
                                <div className="d-box">
                                    <label>Spot Price</label>
                                    <span>₹{dialogData.spotPrice}</span>
                                </div>
                                <div className="d-box">
                                    <label>Daily Trend</label>
                                    <span>{dialogData.dailyTrend || '-'}</span>
                                </div>
                                <div className="d-box">
                                    <label>Entry Premium</label>
                                    <span>₹{dialogData.entryPremium || '-'}</span>
                                </div>

                                <div className="d-box">
                                    <label>Live/Current Premium</label>
                                    <span>
                                        {dialogData.status === 'ACTIVE' && dialogData.currentPremium
                                            ? `₹${dialogData.currentPremium}`
                                            : '-'}
                                    </span>
                                </div>

                                <div className="d-box">
                                    <label>Exit Premium</label>
                                    <span>{dialogData.exitPremium ? `₹${dialogData.exitPremium}` : (dialogData.status === 'ACTIVE' ? 'LIVE' : '-')}</span>
                                </div>

                                <div className="d-box">
                                    <label>Realized PnL</label>
                                    <span>{dialogData.status === 'HISTORY' ? formatPnL(dialogData.realizedPnl) : '-'}</span>
                                </div>
                            </div>

                            <div className="d-box highlight-box" style={{ marginBottom: '20px' }}>
                                <label>Unrealized (MTM) PnL</label>
                                <span style={{ fontSize: '1.2rem' }}>
                                    {dialogData.status === 'ACTIVE' ? formatPnL(dialogData.unrealizedPnl) : 'N/A (Trade Closed)'}
                                </span>
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