import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Activity, ArrowDownToLine, ArrowUpFromLine, Check, ChevronRight, CircleHelp, Gauge, Globe2, Home, Network, Plus, RefreshCw, Router, Settings2, ShieldCheck, Smartphone, Trash2, Wifi, X } from 'lucide-react';
import { getNativeBridge, type NativeAppSnapshot, type NativeRoutingRule, type NativeClientSettings, type NativeCommand } from './nativeBridge';

type Page = 'home' | 'servers' | 'routing' | 'settings';
const pages: { id: Page; label: string; icon: typeof Home }[] = [
  { id: 'home', label: 'Главная', icon: Home },
  { id: 'servers', label: 'Серверы', icon: Router },
  { id: 'routing', label: 'Маршрутизация', icon: Network },
  { id: 'settings', label: 'Настройки', icon: Settings2 },
];

function bytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) return '—';
  const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let amount = value, unit = 0;
  while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
  return `${amount.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}
function rate(value: number): string { return `${bytes(value)}/с`; }

export default function App() {
  const [page, setPage] = useState<Page>('home');
  const [snapshot, setSnapshot] = useState<NativeAppSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState('Подключаемся к системному сервису…');
  const [draftDomain, setDraftDomain] = useState<Record<string, string>>({});
  const bridge = getNativeBridge();

  const refresh = useCallback(async () => {
    const native = getNativeBridge();
    if (!native) {
      setLoading(false);
      setMessage('Нативный адаптер ещё не подключён. Интерфейс не будет имитировать соединение или сетевую статистику.');
      return;
    }
    const response = await native.request<NativeAppSnapshot>({ type: 'app.getState' });
    if (response.ok) {
      setSnapshot(response.result);
      setMessage('');
    } else {
      setMessage(response.error.message);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    void refresh();
    const native = getNativeBridge();
    if (!native) return;
    return native.subscribe(event => {
      if (event.type === 'app.stateChanged') setSnapshot(event.snapshot);
      if (event.type === 'connection.changed') setSnapshot(prev => prev ? { ...prev, connection: event.state } : prev);
      if (event.type === 'traffic.updated') setSnapshot(prev => prev ? { ...prev, traffic: event.stats } : prev);
    });
  }, [refresh]);

  const runCommand = async (command: NativeCommand) => {
    const native = getNativeBridge();
    if (!native) { setMessage('Нативный адаптер недоступен. Действие не выполнено.'); return; }
    setMessage('');
    try {
      const response = await native.request(command);
      if (!response.ok) setMessage(response.error.message);
      await refresh();
    } catch {
      setMessage('Не удалось связаться с системным сервисом. Проверьте состояние приложения.');
    }
  };

  const saveRouting = async (rules: NativeRoutingRule[]) => {
    await runCommand({ type: 'routing.save', payload: { rules } });
  };
  const saveSettings = async (settings: NativeClientSettings) => {
    await runCommand({ type: 'settings.save', payload: settings });
  };

  const connection = snapshot?.connection;
  const connected = connection?.status === 'connected';
  const activeProfile = snapshot?.profiles.find(profile => profile.selected);
  const statusLabel = connection?.status === 'connected' ? 'Подключено' :
    connection?.status === 'connecting' ? 'Подключение…' :
    connection?.status === 'error' ? 'Ошибка соединения' : 'Отключено';

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><div className="brand-mark">N</div><div><b>NeoTUN</b><span>CLIENT</span></div></div>
        <div className="nav-caption">РАБОЧЕЕ ПРОСТРАНСТВО</div>
        <nav>{pages.map(item => { const Icon = item.icon; return <button key={item.id} className={page === item.id ? 'nav-item active' : 'nav-item'} onClick={() => setPage(item.id)}><Icon size={18}/><span>{item.label}</span>{page === item.id && <ChevronRight size={15}/>}</button>; })}</nav>
        <div className="sidebar-status"><span className={connected ? 'status-dot on' : 'status-dot'} /><div><small>СОСТОЯНИЕ</small><b>{statusLabel}</b><span>{activeProfile?.name ?? 'Сервер не выбран'}</span></div></div>
        <div className="sidebar-footer">NeoTUN · {snapshot?.platform ?? 'UI preview'}</div>
      </aside>

      <main className="main-area">
        <header className="topbar"><div><div className="eyebrow">NEOTUN / CLIENT</div><h1>{pages.find(item => item.id === page)?.label}</h1></div><div className="topbar-actions"><span className="version">{snapshot ? `v${snapshot.appVersion}` : 'SHARED UI'}</span><button className="icon-button" title="Обновить состояние" onClick={() => void refresh()}><RefreshCw size={17}/></button></div></header>
        {message && <div className="notice"><CircleHelp size={18}/><span>{message}</span></div>}
        {loading ? <div className="loading-panel"><RefreshCw className="spin" size={22}/><span>Загрузка состояния…</span></div> : (
          <>
            {page === 'home' && <section className="page-content">
              <div className="hero-card">
                <div className="hero-copy"><div className={connected ? 'connection-pill connected' : 'connection-pill'}><span className="status-dot"/>{statusLabel.toUpperCase()}</div><h2>{connected ? 'Вы в сети' : 'Готовы к работе?'}</h2><p>{activeProfile ? activeProfile.name : 'Выберите сервер, чтобы начать работу.'}</p><div className="server-chip"><Globe2 size={17}/><div><small>ТЕКУЩИЙ СЕРВЕР</small><b>{activeProfile?.endpoint ?? 'Не выбран'}</b></div><button onClick={() => setPage('servers')} aria-label="Выбрать сервер"><ChevronRight size={18}/></button></div></div>
                <button className={connected ? 'power-button connected' : 'power-button'} disabled={!snapshot?.capabilities.connectionLifecycle || connection?.status === 'connecting' || !snapshot?.profiles.length} onClick={() => void runCommand(connected ? {type:'connection.disconnect'} : {type:'connection.connect',payload:{profileId:activeProfile?.id}})}><Wifi size={34}/><span>{connected ? 'ОТКЛЮЧИТЬ' : 'ПОДКЛЮЧИТЬ'}</span></button>
              </div>
              <div className="stats-grid">
                <Stat icon={<Gauge size={17}/>} label="СКОРОСТЬ ЗАГРУЗКИ" value={snapshot ? rate(snapshot.traffic.downloadBytesPerSecond) : '—'} />
                <Stat icon={<ArrowDownToLine size={17}/>} label="ПОЛУЧЕНО" value={snapshot ? bytes(snapshot.traffic.receivedBytes) : '—'} />
                <Stat icon={<ArrowUpFromLine size={17}/>} label="ОТПРАВЛЕНО" value={snapshot ? bytes(snapshot.traffic.sentBytes) : '—'} />
              </div>
              <div className="section-heading"><div><h3>Быстрый доступ</h3><p>Управляйте основными параметрами клиента</p></div></div>
              <div className="quick-grid"><QuickCard icon={<Router/>} title="Серверы" description={`${snapshot?.profiles.length ?? 0} профилей`} onClick={() => setPage('servers')}/><QuickCard icon={<Network/>} title="Маршрутизация" description="Правила и DNS" onClick={() => setPage('routing')}/><QuickCard icon={<Settings2/>} title="Настройки" description="Сеть и приложение" onClick={() => setPage('settings')}/></div>
            </section>}

            {page === 'servers' && <section className="page-content">
              <div className="section-heading"><div><h3>Ваши серверы</h3><p>Выберите профиль для подключения</p></div><button className="primary small" disabled={!snapshot?.capabilities.profileManagement} onClick={() => { const source = window.prompt('Вставьте ссылку или конфигурацию профиля'); if (source?.trim()) void runCommand({type:'profiles.import',payload:{source:source.trim()}}); }}><Plus size={16}/> Импорт</button></div>
              {!snapshot?.profiles.length ? <EmptyState title="Профилей пока нет" text="Импортируйте ссылку или конфигурацию, чтобы добавить сервер."/> : <div className="profile-list">{snapshot.profiles.map(profile => <button key={profile.id} className={profile.selected ? 'profile-card selected' : 'profile-card'} onClick={() => void runCommand({type:'profiles.select',payload:{profileId:profile.id}})}><div className="profile-icon"><Globe2 size={20}/></div><div className="profile-info"><b>{profile.name}</b><span>{profile.endpoint}</span><small>{profile.protocol.toUpperCase()}</small></div>{profile.selected ? <span className="selected-badge"><Check size={14}/> Выбран</span> : <ChevronRight size={18}/>}</button>)}</div>}
            </section>}

            {page === 'routing' && <section className="page-content">
              <div className="section-heading"><div><h3>Правила трафика</h3><p>Настройте, куда направлять совпавшие соединения</p></div><span className="capability-tag">{snapshot?.capabilities.routing ? 'Доступно' : 'Ожидает адаптер'}</span></div>
              <div className="route-intro"><div className="route-action proxy"><Globe2/><div><b>Proxy</b><span>Через выбранный сервер</span></div></div><div className="route-action direct"><ArrowUpFromLine/><div><b>Direct</b><span>Напрямую через сеть</span></div></div><div className="route-action block"><X/><div><b>Block</b><span>Запретить соединение</span></div></div></div>
              {!snapshot?.routingRules.length ? <EmptyState title="Правила не загружены" text="Когда нативный адаптер будет подключён, здесь появятся правила из активного профиля."/> : <div className="rules-list">{snapshot.routingRules.map(rule => <div className="rule-card" key={rule.id}><div className="rule-header"><div className="rule-title"><span className={`rule-dot ${rule.action}`}/><div><b>{rule.action.toUpperCase()}</b><small>{rule.domains.length} доменов · {rule.ips.length} IP/CIDR</small></div></div><label className="switch"><input type="checkbox" checked={rule.enabled} disabled={!snapshot.capabilities.routing} onChange={e => void saveRouting(snapshot.routingRules.map(item => item.id === rule.id ? {...item,enabled:e.target.checked} : item))}/><span/></label></div><div className="rule-chips">{[...rule.domains,...rule.ips].map(value => <span key={value}>{value}</span>)}</div><form className="add-rule" onSubmit={e => {e.preventDefault();const value=(draftDomain[rule.id]??'').trim();if(!value)return;void saveRouting(snapshot.routingRules.map(item=>item.id===rule.id?{...item,domains:item.domains.includes(value)?item.domains:[...item.domains,value]}:item));setDraftDomain(prev=>({...prev,[rule.id]:''}));}}><input value={draftDomain[rule.id]??''} onChange={e=>setDraftDomain(prev=>({...prev,[rule.id]:e.target.value}))} placeholder="Добавить домен"/><button className="icon-button" type="submit" disabled={!snapshot.capabilities.routing}><Plus size={16}/></button></form></div>)}</div>}
              <div className="inline-note"><ShieldCheck size={17}/><span>Изменения считаются применёнными только после подтверждения от нативного движка. Для некоторых движков может потребоваться переподключение.</span></div>
            </section>}

            {page === 'settings' && <SettingsPage snapshot={snapshot} onSave={saveSettings} onRun={runCommand}/>}
          </>
        )}
      </main>
      <nav className="mobile-nav">{pages.map(item => {const Icon=item.icon;return <button key={item.id} className={page===item.id?'mobile-nav-item active':'mobile-nav-item'} onClick={()=>setPage(item.id)}><Icon size={19}/><span>{item.label}</span></button>;})}</nav>
    </div>
  );
}

function Stat({icon,label,value}:{icon:ReactNode;label:string;value:string}) {return <div className="stat-card"><div className="stat-icon">{icon}</div><span>{label}</span><b>{value}</b></div>;}
function QuickCard({icon,title,description,onClick}:{icon:ReactNode;title:string;description:string;onClick:()=>void}) {return <button className="quick-card" onClick={onClick}><span className="quick-icon">{icon}</span><b>{title}</b><small>{description}</small><ChevronRight className="quick-chevron" size={16}/></button>;}
function EmptyState({title,text}:{title:string;text:string}) {return <div className="empty-state"><div className="empty-icon"><Activity size={22}/></div><b>{title}</b><p>{text}</p></div>;}

function SettingsPage({snapshot,onSave,onRun}:{snapshot:NativeAppSnapshot|null;onSave:(settings:NativeClientSettings)=>Promise<void>;onRun:(command:NativeCommand)=>Promise<void>}) {
  const [draft,setDraft] = useState<NativeClientSettings|null>(snapshot?.settings??null);
  useEffect(()=>setDraft(snapshot?.settings??null),[snapshot?.settings]);
  if (!draft || !snapshot) return <section className="page-content"><EmptyState title="Настройки недоступны" text="Сначала подключите нативный адаптер. Значения не будут подменяться демонстрационными."/></section>;
  const update = (patch:Partial<NativeClientSettings>)=>setDraft(current=>current?{...current,...patch}:current);
  return <section className="page-content">
    <div className="section-heading"><div><h3>Параметры клиента</h3><p>Текущие значения, полученные от платформы</p></div><button className="primary small" disabled={!snapshot.capabilities.dns&&!snapshot.capabilities.mtu&&!snapshot.capabilities.ipv6} onClick={()=>void onSave(draft)}>Сохранить</button></div>
    <div className="settings-card"><div className="settings-card-title"><Globe2 size={18}/><div><b>DNS</b><span>Разрешение доменных имён</span></div></div><label className="field-label">Удалённый DNS<input value={draft.dns.remote} onChange={e=>update({dns:{...draft.dns,remote:e.target.value}})} placeholder="https://8.8.8.8/dns-query"/></label><label className="field-label">Домашний DNS<input value={draft.dns.domestic} onChange={e=>update({dns:{...draft.dns,domestic:e.target.value}})} placeholder="https://77.88.8.8/dns-query"/></label><label className="field-label">Домены домашнего DNS<textarea value={draft.dns.domesticDomains.join('\n')} onChange={e=>update({dns:{...draft.dns,domesticDomains:e.target.value.split('\n').map(s=>s.trim()).filter(Boolean)}})} rows={3} placeholder="ru\nsu\nрф"/></label></div>
    <div className="settings-card"><div className="settings-card-title"><Network size={18}/><div><b>Сеть</b><span>Параметры сетевого интерфейса</span></div></div><label className="field-label">MTU <strong>{draft.mtu} байт</strong><input type="range" min={1280} max={1500} step={10} value={draft.mtu} onChange={e=>update({mtu:Number(e.target.value)})}/></label><label className="field-label">IPv6<select value={draft.ipv6} onChange={e=>update({ipv6:e.target.value as NativeClientSettings['ipv6']})}><option value="prefer-ipv4">Предпочитать IPv4</option><option value="enabled">Включён</option><option value="disabled">Отключён</option></select></label><SettingToggle title="Автоподключение" description="Подключаться при запуске клиента" checked={draft.autoConnect} onChange={value=>update({autoConnect:value})}/><SettingToggle title="Автообновление подписок" description="Обновлять профили по расписанию" checked={draft.autoUpdateSubscriptions} onChange={value=>update({autoUpdateSubscriptions:value})}/></div>
    <div className="settings-card"><div className="settings-card-title"><RefreshCw size={18}/><div><b>Обслуживание</b><span>Диагностика и обновления</span></div></div><button className="settings-action" disabled={!snapshot.capabilities.diagnostics} onClick={()=>void onRun({type:'diagnostics.run'})}><Activity size={17}/><span><b>Проверить соединение</b><small>Запустить системную диагностику</small></span><ChevronRight size={17}/></button><button className="settings-action" disabled={!snapshot.capabilities.appUpdates} onClick={()=>void onRun({type:'app.checkUpdate'})}><RefreshCw size={17}/><span><b>Проверить обновления</b><small>Версия {snapshot.appVersion}</small></span><ChevronRight size={17}/></button></div>
    <div className="inline-note"><Smartphone size={17}/><span>Недоступные настройки отключены. После сохранения платформа должна сообщить, применены ли изменения сразу или нужен перезапуск соединения.</span></div>
  </section>;
}
function SettingToggle({title,description,checked,onChange}:{title:string;description:string;checked:boolean;onChange:(value:boolean)=>void}) {return <div className="setting-toggle"><div><b>{title}</b><span>{description}</span></div><label className="switch"><input type="checkbox" checked={checked} onChange={e=>onChange(e.target.checked)}/><span/></label></div>;}
