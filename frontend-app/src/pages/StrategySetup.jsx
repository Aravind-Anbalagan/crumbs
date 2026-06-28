import { useState, useEffect, useMemo } from 'react';
import api from '../services/api';
import './StrategySetup.css'; // MUST IMPORT CSS HERE

export default function StrategySetup() {
  const [strategies, setStrategies] = useState([]);
  const [loading, setLoading] = useState(true);
  
  // Filter States
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Edit States
  const [editRowId, setEditRowId] = useState(null);
  const [editFormData, setEditFormData] = useState({});

  const userEmail = localStorage.getItem('userEmail');
  const canEdit = userEmail === 'anbalagan.aravind@gmail.com';

  useEffect(() => {
    fetchStrategies();
  }, []);

  const fetchStrategies = () => {
    setLoading(true);
    api.get('/strategies')
      .then(res => {
        setStrategies(res.data);
        setLoading(false);
      })
      .catch(err => {
        console.error("Error fetching strategies:", err);
        setLoading(false);
      });
  };

  // --- FILTER LOGIC ---
  const filteredStrategies = useMemo(() => {
    return strategies.filter(strat => {
      const matchesSearch = 
        strat.name.toLowerCase().includes(searchQuery.toLowerCase()) || 
        (strat.symbol && strat.symbol.toLowerCase().includes(searchQuery.toLowerCase()));
      
      const matchesStatus = statusFilter === 'ALL' ? true : strat.active === statusFilter;
      
      return matchesSearch && matchesStatus;
    });
  }, [strategies, searchQuery, statusFilter]);

  // --- EDIT LOGIC ---
  const handleEditClick = (strat) => {
    if (!canEdit) return; 
    setEditRowId(strat.id);
    setEditFormData({ 
      name: strat.name, active: strat.active, exchange: strat.exchange,
      expiry: strat.expiry, live: strat.live, symbol: strat.symbol,
      token: strat.token, tradingsymbol: strat.tradingsymbol, quantity: strat.quantity
    });
  };

  const handleCancelClick = () => {
    setEditRowId(null);
    setEditFormData({});
  };

  const handleInputChange = (field, value) => {
    setEditFormData({ ...editFormData, [field]: value });
  };

  const handleSaveClick = async (id) => {
    if (!canEdit) return;
    try {
      const response = await api.put(`/strategies/${id}`, editFormData);
      setStrategies(strategies.map(strat => strat.id === id ? response.data : strat));
      setEditRowId(null);
    } catch (error) {
      console.error("Error saving strategy:", error);
      alert("Failed to save. Check console for details.");
    }
  };

  const columns = [
    { key: 'name', label: 'Name', type: 'text' },
    { key: 'active', label: 'Active', type: 'text' },
    { key: 'exchange', label: 'Exchange', type: 'text' },
    { key: 'expiry', label: 'Expiry', type: 'text' },
    { key: 'live', label: 'Live', type: 'text' },
    { key: 'symbol', label: 'Symbol', type: 'text' },
    { key: 'token', label: 'Token', type: 'text' },
    { key: 'tradingsymbol', label: 'Trading Symbol', type: 'text' },
    { key: 'quantity', label: 'Qty', type: 'number' }
  ];

  return (
    <div className="strategy-wrapper">
      
      {/* HEADER & FILTERS */}
      <header className="strategy-header">
        <div>
          <h1 style={{ margin: 0, fontWeight: 700, fontSize: '1.8rem' }}>Strategy Configuration</h1>
          <p style={{ margin: '5px 0 0 0', opacity: 0.7 }}>
            Manage core execution parameters.
            <span style={{ marginLeft: '10px', color: canEdit ? '#22c55e' : '#ef4444', fontWeight: 'bold' }}>
              [{canEdit ? 'Admin Access' : 'View Only'}]
            </span>
          </p>
        </div>

        <div className="strategy-controls">
          <input 
            type="text" 
            placeholder="Search Name or Symbol..." 
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="rich-input"
          />
          <select 
            value={statusFilter} 
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rich-select"
          >
            <option value="ALL">All Status</option>
            <option value="Y">Active Only (Y)</option>
            <option value="N">Inactive Only (N)</option>
          </select>
          <button onClick={fetchStrategies} className="rich-btn btn-primary">
            ↻ Sync
          </button>
        </div>
      </header>

      {/* TABLE */}
      <div className="table-glass-container">
        {loading ? (
          <div style={{ padding: '3rem', textAlign: 'center', fontWeight: 'bold' }}>Synchronizing Engine Data...</div>
        ) : (
          <table className="rich-table">
            <thead>
              <tr>
                <th>ID</th>
                {columns.map(col => <th key={col.key}>{col.label}</th>)}
                <th className="sticky-actions-header">Actions</th>
              </tr>
            </thead>

            <tbody>
              {filteredStrategies.length === 0 ? (
                <tr><td colSpan={columns.length + 2} style={{ textAlign: 'center', padding: '2rem' }}>No strategies found matching your filters.</td></tr>
              ) : (
                filteredStrategies.map(strat => {
                  const isEditing = editRowId === strat.id;

                  return (
                    <tr key={strat.id} className={isEditing ? 'row-editing' : ''}>
                      <td style={{ fontWeight: 'bold', opacity: 0.8 }}>#{strat.id}</td>
                      
                      {columns.map(col => (
                        <td key={col.key}>
                          {isEditing ? (
                            <input 
                              type={col.type} 
                              value={editFormData[col.key] || ''} 
                              onChange={(e) => handleInputChange(col.key, e.target.value)}
                              className="rich-input"
                              style={{ width: col.type === 'number' ? '80px' : (col.key === 'tradingsymbol' ? '180px' : '120px') }}
                            />
                          ) : (
                            // Rich Status Badges for Y/N fields
                            (col.key === 'active' || col.key === 'live') ? (
                              <span className={`badge ${strat[col.key] === 'Y' ? 'badge-active' : 'badge-inactive'}`}>
                                {strat[col.key] === 'Y' ? '● YES' : '○ NO'}
                              </span>
                            ) : (
                              <span style={{ fontWeight: col.key === 'name' ? '600' : 'normal' }}>
                                {strat[col.key] || '-'}
                              </span>
                            )
                          )}
                        </td>
                      ))}

                      <td className="sticky-actions-cell">
                        {isEditing ? (
                          <div style={{ display: 'flex', gap: '8px' }}>
                            <button onClick={() => handleSaveClick(strat.id)} className="rich-btn btn-success">Save</button>
                            <button onClick={handleCancelClick} className="rich-btn btn-danger">Cancel</button>
                          </div>
                        ) : (
                          <button 
                            onClick={() => handleEditClick(strat)} 
                            disabled={!canEdit}
                            title={!canEdit ? "Admin permissions required" : "Edit Strategy"}
                            className={`rich-btn ${canEdit ? 'btn-primary' : 'btn-disabled'}`}
                          >
                            {canEdit ? 'Edit' : '🔒 Locked'}
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}