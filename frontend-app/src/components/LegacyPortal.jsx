export default function LegacyPortal({ srcFile }) {
  return (
    <>
      <style>{`
        .portal-wrapper {
          flex: 1;
          display: flex;
          flex-direction: column;
          overflow: hidden;
          background: var(--bg-main);
          padding: 0;
          margin: 0;
        }

        .portal-iframe {
          width: 100%;
          height: 100%;
          border: none;
          overflow: hidden;
        }

        /* Glass border effect to transition from the layout to the legacy page */
        .portal-border {
          border: 1px solid var(--glass-border);
          border-radius: 12px;
          overflow: hidden;
          flex: 1;
          display: flex;
        }
      `}</style>

      <div className="portal-wrapper">
        <div className="portal-border">
          <iframe
            src={srcFile}
            title="Legacy Trading View"
            className="portal-iframe"
            allow="fullscreen" 
          />
        </div>
      </div>
    </>
  );
}