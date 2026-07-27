import { useCallback, useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { Icon } from '../common/Icon';

type QrChannel = 'feishu' | 'dingtalk';

export function ChannelQrBind({ channel, backendPort, sessionId, onBound }: {
  channel: QrChannel;
  backendPort?: number | null;
  sessionId?: string;
  onBound: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [image, setImage] = useState('');
  const [status, setStatus] = useState('');
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const safeSessionId = sessionId || 'default';
  const label = channel === 'feishu' ? '飞书' : '钉钉';

  const stopTimers = useCallback(() => {
    if (pollRef.current) clearInterval(pollRef.current);
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    pollRef.current = null;
    timeoutRef.current = null;
  }, []);

  const close = useCallback(async () => {
    stopTimers(); setOpen(false); setImage(''); setStatus('');
    if (backendPort) {
      await fetch(`http://localhost:${backendPort}/web/chat/${channel}/qrcode/cancel?sessionId=${encodeURIComponent(safeSessionId)}`, { method: 'POST' }).catch(() => undefined);
    }
  }, [backendPort, channel, safeSessionId, stopTimers]);

  useEffect(() => () => { stopTimers(); }, [stopTimers]);

  const start = useCallback(async () => {
    if (!backendPort || loading) return;
    stopTimers(); setLoading(true); setStatus('正在获取二维码...');
    try {
      const response = await fetch(`http://localhost:${backendPort}/web/chat/${channel}/qrcode?sessionId=${encodeURIComponent(safeSessionId)}`, { method: 'POST' });
      if (!response.ok) throw new Error();
      const payload = await response.json();
      if (payload?.code !== undefined && payload.code !== 200) throw new Error();
      const qrUrl = String(payload?.data?.qrUrl || '');
      if (!qrUrl || qrUrl.length > 8192) throw new Error();
      const dataUrl = await QRCode.toDataURL(qrUrl, { width: 220, margin: 1, errorCorrectionLevel: 'M' });
      setImage(dataUrl); setOpen(true); setStatus(`请使用${label} App 扫码授权`);
      const intervalMs = Math.max(1500, Math.min(10_000, Number(payload?.data?.interval || 2) * 1000));
      pollRef.current = setInterval(async () => {
        try {
          const pollResponse = await fetch(`http://localhost:${backendPort}/web/chat/${channel}/qrcode/status?sessionId=${encodeURIComponent(safeSessionId)}`, { cache: 'no-store' });
          const pollPayload = await pollResponse.json();
          const data = pollPayload?.data || {};
          if (data.bound || data.status === 'success') {
            stopTimers(); setOpen(false); setImage(''); onBound();
          } else if (data.status === 'failed' || data.status === 'error') {
            stopTimers(); setStatus('授权失败，请关闭后重试');
          } else {
            setStatus(data.message || `等待${label}授权...`);
          }
        } catch { stopTimers(); setStatus('状态查询失败，请重试'); }
      }, intervalMs);
      const expiresMs = Math.max(30_000, Math.min(10 * 60_000, Number(payload?.data?.expiresIn || 120) * 1000));
      timeoutRef.current = setTimeout(() => { stopTimers(); setStatus('二维码已过期，请关闭后重试'); }, expiresMs);
    } catch { setStatus('二维码获取失败'); }
    finally { setLoading(false); }
  }, [backendPort, channel, label, loading, onBound, safeSessionId, stopTimers]);

  return <>
    <button className="channel-btn bind" onClick={() => void start()} disabled={loading}>{loading ? '获取中...' : '扫码'}</button>
    {open && <div className="qrcode-overlay" onClick={() => void close()}><div className="qrcode-modal" onClick={event => event.stopPropagation()}>
      {image && <img src={image} alt={`${label}绑定二维码`} className="qrcode-modal-img" />}
      <p className="qrcode-modal-hint">{status}</p>
      <button className="qrcode-modal-close" onClick={() => void close()}><Icon name="close" size={16} /></button>
    </div></div>}
  </>;
}
