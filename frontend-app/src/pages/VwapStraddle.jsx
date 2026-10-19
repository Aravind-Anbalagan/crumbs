import React, { useState, useEffect, useMemo } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, Legend, ResponsiveContainer, ReferenceDot
} from 'recharts';
import './vwapStraddle.css';

const VwapStraddle = () => {
  // Use relative path to rely on Vite's proxy in development,
  // and natural same-domain routing in production.
  // This completely eliminates CORS issues and the HTML fallback issue.
  const API_BASE = '/api/options/scanner/vwap';

  const [symbols, setSymbols] = useState([]);
  const [selectedSymbol, setSelectedSymbol] = useState('');

  const [chartDataDto, setChartDataDto] = useState(null);
  const [selectedStrike, setSelectedStrike] = useState('');

  const [loadingSymbols, setLoadingSymbols] = useState(true);
  const [loadingChart, setLoadingChart] = useState(false);
  const [error, setError] = useState(null);

  // 1. Fetch available symbols on initial load
  useEffect(() => {
    const fetchSymbols = async () => {
      setLoadingSymbols(true);
      setError(null);
      try {
        const response = await fetch(`${API_BASE}/symbols`, {
          headers: { 'Accept': 'application/json' }
        });

        // Strict JSON check: If Vite serves index.html instead of hitting the proxy, this catches it
        const contentType = response.headers.get("content-type");
        if (!contentType || !contentType.includes("application/json")) {
          throw new Error("Received HTML instead of JSON. Ensure Vite proxy is running.");
        }

        if (!response.ok) throw new Error(`Failed to fetch symbols: ${response.status}`);

        const data = await response.json();
        setSymbols(data);

        // Auto-select the first symbol to trigger the chart load automatically
        if (data && data.length > 0) {
          setSelectedSymbol(data[0]);
        }
      } catch (err) {
        setError(err.message);
      } finally {
        setLoadingSymbols(false);
      }
    };
    fetchSymbols();
  }, []);

  // 2. Fetch Chart DTO when selectedSymbol changes
  useEffect(() => {
    if (!selectedSymbol) return;

    const fetchChartData = async () => {
      setLoadingChart(true);
      setError(null);
      try {
        const url = `${API_BASE}/chart?symbol=${selectedSymbol}`;
        const response = await fetch(url, {
          headers: { 'Accept': 'application/json' }
        });

        if (!response.ok) throw new Error(`API error: ${response.status}`);

        const dto = await response.json();
        setChartDataDto(dto);

        // Auto-select the ATM strike, or fallback to the first available strike
        if (dto.currentAtmStrike) {
           setSelectedStrike(formatStrikeKey(dto.currentAtmStrike));
        } else if (dto.availableStrikes && dto.availableStrikes.length > 0) {
           setSelectedStrike(formatStrikeKey(dto.availableStrikes[0]));
        } else {
           setSelectedStrike('');
        }
      } catch (err) {
        setError(err.message);
      } finally {
        setLoadingChart(false);
      }
    };

    fetchChartData();
  }, [selectedSymbol]);

  // Handle Dropdown changes
  const handleSymbolChange = (e) => setSelectedSymbol(e.target.value);
  const handleStrikeChange = (e) => setSelectedStrike(e.target.value);

  // Helper to safely format strike keys matching JSON map (e.g., "122.0")
  const formatStrikeKey = (val) => {
    return Number.isInteger(Number(val)) ? `${val}.0` : `${val}`;
  };

  // 3. Process the data for the Recharts graph
  const processedData = useMemo(() => {
    if (!chartDataDto || !chartDataDto.strikeData || !selectedStrike) return [];

    // Find the strike data array (try direct string, then float string, then raw)
    const strikeKey = selectedStrike;
    const fallbackKey = selectedStrike.replace('.0', '');
    const strikeSeries = chartDataDto.strikeData[strikeKey] || chartDataDto.strikeData[fallbackKey];

    if (!strikeSeries) return [];

    const chartData = [];
    for (let i = 0; i < strikeSeries.length; i++) {
      const current = strikeSeries[i];
      const previous = i > 0 ? strikeSeries[i - 1] : null;
      let signal = null;

      if (previous) {
        const wasAbove = previous.combinedLtp > previous.combinedVwap;
        const isBelow = current.combinedLtp < current.combinedVwap;
        const wasBelow = previous.combinedLtp < previous.combinedVwap;
        const isAbove = current.combinedLtp > current.combinedVwap;

        if (wasAbove && isBelow) signal = 'ENTRY';
        else if (wasBelow && isAbove) signal = 'SL';
      }

      const date = new Date(current.evaluatedAt);
      const timeString = `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`;

      chartData.push({ ...current, timeString, signal });
    }
    return chartData;
  }, [chartDataDto, selectedStrike]);

  const signalDots = useMemo(() => processedData.filter(d => d.signal !== null), [processedData]);

  const CustomTooltip = ({ active, payload }) => {
    if (active && payload && payload.length) {
      const dataPoint = payload[0].payload;
      return (
        <div className="vwap-straddle-tooltip">
          <p className="tooltip-time">{dataPoint.timeString}</p>
          <p className="tooltip-ltp">Combined LTP: <span>{dataPoint.combinedLtp?.toFixed(2)}</span></p>
          <p className="tooltip-vwap">Combined VWAP: <span>{dataPoint.combinedVwap?.toFixed(2)}</span></p>
          {dataPoint.signal && (
            <p className={`tooltip-signal ${dataPoint.signal.toLowerCase()}`}>
              SIGNAL: {dataPoint.signal}
            </p>
          )}
        </div>
      );
    }
    return null;
  };

  if (loadingSymbols) {
    return <div className="vwap-straddle-full flex-center">Loading Symbols...</div>;
  }

  if (error && !symbols.length) {
    return <div className="vwap-straddle-full flex-center error">Error: {error}</div>;
  }

  return (
    <div className="vwap-straddle-full">
      <div className="vwap-straddle-header">
        <div className="header-title">
          <h2>VWAP Straddle Monitor</h2>
          {chartDataDto?.latestSpotPrice && (
            <span className="spot-price">Spot: {chartDataDto.latestSpotPrice.toFixed(2)}</span>
          )}
        </div>

        <div className="vwap-controls">
          <div className="control-group">
            <label htmlFor="symbol-selector">Symbol</label>
            <select
              id="symbol-selector"
              className="vwap-selector"
              value={selectedSymbol}
              onChange={handleSymbolChange}
            >
              {symbols.map((sym) => (
                <option key={sym} value={sym}>{sym}</option>
              ))}
            </select>
          </div>

          <div className="control-group">
            <label htmlFor="strike-selector">Strike</label>
            <select
              id="strike-selector"
              className="vwap-selector"
              value={selectedStrike}
              onChange={handleStrikeChange}
              disabled={loadingChart || !chartDataDto}
            >
              {chartDataDto?.availableStrikes?.map((strike) => (
                <option key={strike} value={formatStrikeKey(strike)}>
                  {strike}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>

      <div className="vwap-straddle-wrapper">
        {loadingChart ? (
          <div className="chart-overlay">
            <div className="spinner"></div>
            <p>Loading {selectedSymbol} Chart...</p>
          </div>
        ) : processedData.length === 0 ? (
          <div className="flex-center chart-empty">No data available for the selected parameters.</div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart
              data={processedData}
              margin={{ top: 30, right: 30, left: 10, bottom: 20 }}
            >
              <CartesianGrid strokeDasharray="3 3" stroke="#2d2d2d" vertical={false} />
              <XAxis
                dataKey="timeString"
                stroke="#888"
                minTickGap={40}
                tickMargin={10}
              />
              <YAxis
                domain={['dataMin - 0.5', 'dataMax + 0.5']}
                stroke="#888"
                tickFormatter={(val) => val.toFixed(1)}
                tickMargin={10}
              />
              <Tooltip content={<CustomTooltip />} />
              <Legend verticalAlign="top" height={40}/>

              <Line
                type="monotone"
                dataKey="combinedLtp"
                stroke="#00E676"
                strokeWidth={2.5}
                dot={false}
                name="Combined LTP"
                isAnimationActive={false}
              />
              <Line
                type="monotone"
                dataKey="combinedVwap"
                stroke="#2979FF"
                strokeWidth={2}
                strokeDasharray="5 5"
                dot={false}
                name="Combined VWAP"
                isAnimationActive={false}
              />

              {signalDots.map((dot, index) => (
                <ReferenceDot
                  key={`dot-${index}`}
                  x={dot.timeString}
                  y={dot.combinedLtp}
                  r={5}
                  fill={dot.signal === 'ENTRY' ? '#00E676' : '#FF1744'}
                  stroke="none"
                  label={{
                    position: 'top',
                    value: dot.signal,
                    fill: dot.signal === 'ENTRY' ? '#00E676' : '#FF1744',
                    fontSize: 11,
                    fontWeight: '800'
                  }}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
};

export default VwapStraddle;