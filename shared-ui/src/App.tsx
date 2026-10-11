import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Activity, ArrowDownToLine, ArrowUpFromLine, Check, ChevronRight, CircleHelp, Gauge, Globe2, Home, Network, Plus, RefreshCw, Router, Settings2, ShieldCheck, Smartphone, Trash2, Wifi, X, Pencil, RotateCw, Copy, QrCode, FileText } from 'lucide-react';
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
      if (!response.ok) {
        setMessage(response.error.message);
        return;
      }
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
    <div className={`app-shell theme-${snapshot?.appearance?.theme ?? "dark"} ${snapshot?.appearance?.animations === false ? "reduce-motion" : ""}`} style={{fontSize:`${snapshot?.appearance?.fontScale ?? 100}%`}}>
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
              <div className="section-heading"><div><h3>Ваши серверы</h3><p>Выберите профиль для подключения</p></div><div className="topbar-actions"><button className="secondary small" disabled={!snapshot || (!snapshot.capabilities.subscriptions && snapshot.platform!=='windows')} onClick={() => { const url = window.prompt('Введите HTTP(S)-ссылку подписки'); if (url?.trim()) { const name = window.prompt('Название подписки', 'Подписка')?.trim() || 'Подписка'; if(snapshot?.platform==='windows') void runCommand({type:'profiles.import',payload:{source:url.trim()}}); else void runCommand({type:'subscriptions.create',payload:{name,url:url.trim()}}); } }}><Plus size={16}/> Подписка</button><button className="primary small" disabled={!snapshot?.capabilities.profileManagement} onClick={() => { const source = window.prompt('Вставьте ссылку или конфигурацию профиля'); if (source?.trim()) void runCommand({type:'profiles.import',payload:{source:source.trim()}}); }}><Plus size={16}/> Импорт</button></div></div>
              {!snapshot?.profiles.length ? <EmptyState title="Профилей пока нет" text="Импортируйте ссылку или конфигурацию, чтобы добавить сервер."/> : <div className="server-list-wrap"><div className="profile-list">{snapshot.profiles.map(profile => <div key={profile.id} role="button" tabIndex={0} className={profile.selected ? 'profile-card selected' : 'profile-card'} onClick={() => void runCommand({type:'profiles.select',payload:{profileId:profile.id}})} onKeyDown={e=>{if(e.key==='Enter'||e.key===' ')void runCommand({type:'profiles.select',payload:{profileId:profile.id}});}}><div className="profile-icon"><Globe2 size={20}/></div><div className="profile-info"><b>{profile.name}</b><span>{profile.endpoint}</span><small>{profile.protocol.toUpperCase()}</small></div>{profile.selected ? <span className="selected-badge"><Check size={14}/> Выбран</span> : <ChevronRight size={18}/>}<span className="profile-actions" onClick={e=>e.stopPropagation()}>{snapshot.platform==='android'&&<>{snapshot.nativeFeatures?.includes('profile-ping')&&<button className="icon-button" title="Проверить пинг" onClick={e=>{e.stopPropagation();void runCommand({type:'profiles.ping',payload:{profileId:profile.id}});}}><Activity size={14}/></button>}{snapshot.nativeFeatures?.includes('profile-config-view')&&<button className="icon-button" title="Показать конфигурацию" onClick={e=>{e.stopPropagation();void runCommand({type:'profiles.showConfig',payload:{profileId:profile.id}});}}><FileText size={14}/></button>}{snapshot.nativeFeatures?.includes('profile-qr-share')&&<button className="icon-button" title="Показать QR-код" onClick={e=>{e.stopPropagation();void runCommand({type:'profiles.showQr',payload:{profileId:profile.id}});}}><QrCode size={14}/></button>}</>}{snapshot.platform==='windows'&&<><button className="icon-button" title="Редактировать профиль" onClick={e=>{e.stopPropagation();const name=window.prompt('Название профиля',profile.name)?.trim();if(!name)return;const uri=window.prompt('Полная ссылка подключения',profile.uri??'');if(uri?.trim())void runCommand({type:'profiles.update',payload:{profileId:profile.id,name,uri:uri.trim()}});}}><Pencil size={14}/></button><button className="icon-button" title="Копировать ссылку" onClick={e=>{e.stopPropagation();void runCommand({type:'profiles.copy',payload:{profileId:profile.id}});}}><Copy size={14}/></button></> }<button className="icon-button" title="Переименовать" disabled={!snapshot.capabilities.profileManagement} onClick={e=>{e.stopPropagation();const name=window.prompt('Новое название профиля',profile.name)?.trim();if(name&&name!==profile.name)void runCommand({type:'profiles.rename',payload:{profileId:profile.id,name}});}}><Pencil size={14}/></button><button className="icon-button danger-button" title="Удалить профиль" disabled={!snapshot.capabilities.profileManagement} onClick={e=>{e.stopPropagation();if(window.confirm(`Удалить профиль «${profile.name}»? Это действие нельзя отменить.`))void runCommand({type:'profiles.delete',payload:{profileId:profile.id}});}}><Trash2 size={14}/></button></span></div>)}</div><div className="profile-footer"><span>{snapshot.profiles.length} профилей</span><button className="secondary small" disabled={(!snapshot.capabilities.subscriptions && snapshot.platform!=='windows') || !snapshot.subscriptions.length} onClick={()=>void runCommand({type:'subscriptions.refresh',payload:{}})}><RotateCw size={14}/> Обновить подписки</button></div></div>}
              <div className="section-heading subscription-heading"><div><h3>Подписки</h3><p>Ссылки, из которых обновляются серверы</p></div><button className="secondary small" disabled={!snapshot?.capabilities.subscriptions} onClick={() => { const value = window.prompt('Интервал автообновления в часах: 1, 6, 12, 24, 72 или 168', '12'); if (value == null) return; const hours = Number(value); if (![1,6,12,24,72,168].includes(hours)) { setMessage('Допустимые интервалы: 1, 6, 12, 24, 72 или 168 часов.'); return; } void runCommand({type:'subscriptions.setSchedule',payload:{enabled:snapshot?.settings.autoUpdateSubscriptions ?? true,intervalHours:hours}}); }}><Settings2 size={14}/> Интервал</button></div>
              {!snapshot?.subscriptions.length ? <EmptyState title="Подписок пока нет" text="Добавьте URL подписки. Список серверов обновится после проверки ссылки."/> : <div className="profile-list subscription-list">{snapshot.subscriptions.map(sub => <div key={sub.id} className="profile-card subscription-card"><div className="profile-icon"><RotateCw size={19}/></div><div className="profile-info"><b>{sub.name}</b><span>{sub.profileCount} серверов · {sub.lastUpdated ? 'Обновлено ' + new Date(sub.lastUpdated).toLocaleString() : 'Ещё не обновлялась'}</span><small>{sub.url}</small></div><span className="profile-actions"><button className="icon-button" title="Обновить подписку" onClick={()=>void runCommand({type:'subscriptions.refresh',payload:{subscriptionId:sub.id}})}><RotateCw size={14}/></button>{snapshot.capabilities.subscriptions&&<><button className="icon-button" title="Переименовать подписку" onClick={()=>{const name=window.prompt('Новое название подписки',sub.name)?.trim();if(name&&name!==sub.name)void runCommand({type:'subscriptions.rename',payload:{subscriptionId:sub.id,name}});}}><Pencil size={14}/></button><button className="icon-button danger-button" title="Удалить подписку" onClick={()=>{if(window.confirm(`Удалить подписку «${sub.name}»? Импортированные серверы останутся в списке.`))void runCommand({type:'subscriptions.delete',payload:{subscriptionId:sub.id,deleteProfiles:false}});}}><Trash2 size={14}/></button></>}</span></div>)}</div>}
            </section>}

            {page === 'routing' && (
              <section className="page-content">
                <div className="section-heading">
                  <div>
                    <h3>Правила трафика</h3>
                    <p>Настройте, куда направлять совпавшие соединения</p>
                  </div>
                  <div className="section-actions"><span className="capability-tag">
                    {snapshot?.capabilities.routing ? 'Доступно' : 'Только в классическом интерфейсе'}
                  </span>{snapshot?.platform==='android'&&!snapshot.capabilities.routing&&<button className="secondary small" onClick={()=>void runCommand({type:'native.openPage',payload:{page:'Маршрутизация'}})}>Открыть настройки маршрутизации</button>}{snapshot?.platform==='windows'&&!snapshot.capabilities.routing&&<button className="secondary small" onClick={()=>void runCommand({type:'native.openPage',payload:{page:'Маршрутизация'}})}>Открыть все настройки маршрутизации</button>}</div>
                </div>

                <div className="route-intro">
                  <div className="route-action proxy">
                    <Globe2 />
                    <div><b>Proxy</b><span>Через выбранный сервер</span></div>
                  </div>
                  <div className="route-action direct">
                    <ArrowUpFromLine />
                    <div><b>Direct</b><span>Напрямую через сеть</span></div>
                  </div>
                  <div className="route-action block">
                    <X />
                    <div><b>Block</b><span>Запретить соединение</span></div>
                  </div>
                </div>

                {!snapshot?.routingRules.length ? (
                  <EmptyState
                    title="Правила не загружены"
                    text="Когда нативный адаптер будет подключён, здесь появятся правила из активного профиля."
                  />
                ) : (
                  <div className="rules-list">
                    {snapshot.routingRules.map(rule => (
                      <div className="rule-card" key={rule.id}>
                        <div className="rule-header">
                          <div className="rule-title">
                            <span className={`rule-dot ${rule.action}`} />
                            <div>
                              <b>{rule.action.toUpperCase()}</b>
                              <small>{rule.domains.length} доменов · {rule.ips.length} IP/CIDR</small>
                            </div>
                          </div>
                          <div className="rule-tools">
                            <select
                              aria-label="Действие правила"
                              value={rule.action}
                              disabled={!snapshot.capabilities.routing || snapshot.capabilities.routingRuleActions===false}
                              onChange={event => {
                                const action = event.target.value as NativeRoutingRule['action'];
                                void saveRouting(snapshot.routingRules.map(item =>
                                  item.id === rule.id ? { ...item, action } : item
                                ));
                              }}
                            >
                              <option value="proxy">Proxy</option>
                              <option value="direct">Direct</option>
                              <option value="block">Block</option>
                            </select>
                            <label className="switch">
                              <input
                                type="checkbox"
                                checked={rule.enabled}
                                disabled={!snapshot.capabilities.routing || snapshot.capabilities.routingRuleToggles===false}
                                onChange={event => void saveRouting(snapshot.routingRules.map(item =>
                                  item.id === rule.id ? { ...item, enabled: event.target.checked } : item
                                ))}
                              />
                              <span />
                            </label>
                          </div>
                        </div>

                        <div className="rule-chips">
                          {[...rule.domains, ...rule.ips].map(value => <span key={value}>{value}</span>)}
                        </div>

                        <form
                          className="add-rule"
                          onSubmit={event => {
                            event.preventDefault();
                            const value = (draftDomain[rule.id] ?? '').trim();
                            if (!value) return;
                            void saveRouting(snapshot.routingRules.map(item =>
                              item.id === rule.id
                                ? { ...item, domains: item.domains.includes(value) ? item.domains : [...item.domains, value] }
                                : item
                            ));
                            setDraftDomain(previous => ({ ...previous, [rule.id]: '' }));
                          }}
                        >
                          <input
                            value={draftDomain[rule.id] ?? ''}
                            onChange={event => setDraftDomain(previous => ({ ...previous, [rule.id]: event.target.value }))}
                            placeholder="Добавить домен"
                          />
                          <button className="icon-button" type="submit" disabled={!snapshot.capabilities.routing} aria-label="Добавить домен">
                            <Plus size={16} />
                          </button>
                          <button
                            className="icon-button danger-button"
                            type="button"
                            title="Удалить правило"
                            aria-label="Удалить правило"
                            disabled={!snapshot.capabilities.routing}
                            onClick={() => {
                              if (window.confirm('Удалить это правило маршрутизации?')) {
                                void saveRouting(snapshot.routingRules.filter(item => item.id !== rule.id));
                              }
                            }}
                          >
                            <Trash2 size={15} />
                          </button>
                        </form>
                      </div>
                    ))}
                  </div>
                )}

                <div className="inline-note">
                  <ShieldCheck size={17} />
                  <span>Изменения считаются применёнными только после подтверждения от нативного движка. Для некоторых движков может потребоваться переподключение.</span>
                </div>
              </section>
            )}

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
  const [appearance,setAppearance] = useState(snapshot?.appearance ?? {theme:"dark" as const,fontScale:100,animations:true});
  const [pingMethod,setPingMethod] = useState<"tcp"|"icmp"|"http">(snapshot?.pingMethod ?? "tcp");
  useEffect(()=>setDraft(snapshot?.settings??null),[snapshot?.settings]);
  useEffect(()=>setAppearance(snapshot?.appearance ?? {theme:"dark",fontScale:100,animations:true}),[snapshot?.appearance]);
  useEffect(()=>setPingMethod(snapshot?.pingMethod ?? "tcp"),[snapshot?.pingMethod]);
  if (!draft || !snapshot) return <section className="page-content"><EmptyState title="Настройки недоступны" text="Сначала подключите нативный адаптер. Значения не будут подменяться демонстрационными."/></section>;
  const update = (patch:Partial<NativeClientSettings>)=>setDraft(current=>current?{...current,...patch}:current);
  return <section className="page-content">
    <div className="section-heading"><div><h3>Параметры клиента</h3><p>Текущие значения, полученные от платформы</p></div><button className="primary small" disabled={!snapshot.capabilities.dns&&!snapshot.capabilities.mtu&&!snapshot.capabilities.ipv6&&!snapshot.capabilities.autoConnect&&!snapshot.capabilities.autoUpdateSubscriptions} onClick={()=>void onSave(draft)}>Сохранить</button></div>
    {snapshot.platform==='android'&&<div className="settings-card"><div className="settings-card-title"><Settings2 size={18}/><div><b>Оформление</b><span>Настройки старого клиента, теперь доступны здесь</span></div></div><label className="field-label">Тема<select value={appearance.theme} onChange={e=>setAppearance(v=>({...v,theme:e.target.value as typeof v.theme}))}><option value="dark">Тёмная</option><option value="light">Светлая</option><option value="oled">OLED-чёрная</option></select></label><label className="field-label">Размер текста <strong>{appearance.fontScale}%</strong><input type="range" min={85} max={130} step={5} value={appearance.fontScale} onChange={e=>setAppearance(v=>({...v,fontScale:Number(e.target.value)}))}/></label><SettingToggle title="Анимации интерфейса" description="Плавные переходы и эффекты" checked={appearance.animations} disabled={false} onChange={value=>setAppearance(v=>({...v,animations:value}))}/><button className="primary small" onClick={()=>void onRun({type:'settings.appearance.save',payload:appearance})}>Сохранить оформление</button><label className="field-label">Метод проверки пинга<select value={pingMethod} onChange={e=>setPingMethod(e.target.value as typeof pingMethod)}><option value="tcp">TCP</option><option value="icmp">ICMP</option><option value="http">Через прокси · HTTP</option></select></label><button className="secondary small" onClick={()=>void onRun({type:'settings.pingMethod.save',payload:{method:pingMethod}})}>Сохранить метод пинга</button></div>}
    <div className="settings-card"><div className="settings-card-title"><Globe2 size={18}/><div><b>DNS</b><span>Разрешение доменных имён</span></div></div><label className="field-label">Удалённый DNS<input disabled={!snapshot.capabilities.dns} value={draft.dns.remote} onChange={e=>update({dns:{...draft.dns,remote:e.target.value}})} placeholder="https://8.8.8.8/dns-query"/></label><label className="field-label">Домашний DNS<input disabled={!snapshot.capabilities.dns} value={draft.dns.domestic} onChange={e=>update({dns:{...draft.dns,domestic:e.target.value}})} placeholder="https://77.88.8.8/dns-query"/></label><label className="field-label">Домены домашнего DNS<textarea disabled={!snapshot.capabilities.dns} value={draft.dns.domesticDomains.join('\n')} onChange={e=>update({dns:{...draft.dns,domesticDomains:e.target.value.split('\n').map(s=>s.trim()).filter(Boolean)}})} rows={3} placeholder="ru\nsu\nрф"/></label></div>
    <div className="settings-card"><div className="settings-card-title"><Network size={18}/><div><b>Сеть</b><span>Параметры сетевого интерфейса</span></div></div><label className="field-label">MTU <strong>{draft.mtu} байт</strong><input disabled={!snapshot.capabilities.mtu} type="range" min={1280} max={1500} step={10} value={draft.mtu} onChange={e=>update({mtu:Number(e.target.value)})}/></label><label className="field-label">IPv6<select disabled={!snapshot.capabilities.ipv6} value={draft.ipv6} onChange={e=>update({ipv6:e.target.value as NativeClientSettings['ipv6']})}>{snapshot.platform !== 'windows' && <option value="prefer-ipv4">Предпочитать IPv4</option>}<option value="enabled">Включён</option><option value="disabled">Отключён</option></select></label><SettingToggle title="Автоподключение" description="Подключаться при запуске клиента" checked={draft.autoConnect} disabled={!snapshot.capabilities.autoConnect} onChange={value=>update({autoConnect:value})}/><SettingToggle title="Автообновление подписок" description="Обновлять профили по расписанию" checked={draft.autoUpdateSubscriptions} disabled={!snapshot.capabilities.autoUpdateSubscriptions} onChange={value=>update({autoUpdateSubscriptions:value})}/></div>
    <div className="settings-card"><div className="settings-card-title"><RefreshCw size={18}/><div><b>Обслуживание</b><span>Диагностика и обновления</span></div></div><button className="settings-action" disabled={!snapshot.capabilities.diagnostics} onClick={()=>void onRun({type:'diagnostics.run'})}><Activity size={17}/><span><b>Проверить соединение</b><small>Запустить системную диагностику</small></span><ChevronRight size={17}/></button><button className="settings-action" disabled={!snapshot.capabilities.appUpdates} onClick={()=>void onRun({type:'app.checkUpdate'})}><RefreshCw size={17}/><span><b>Проверить обновления</b><small>Версия {snapshot.appVersion}</small></span><ChevronRight size={17}/></button></div>
    {snapshot.platform==='android'&&<div className="settings-card"><div className="settings-card-title"><Smartphone size={18}/><div><b>Дополнительные функции Android</b><span>Открывают существующие системные экраны без потери настроек</span></div></div><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Импорт'}})}><Plus size={17}/><span><b>Импорт ссылок и QR</b><small>Буфер обмена, ручной ввод, QR-сканер и JSON</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Маршрутизация'}})}><Network size={17}/><span><b>Профили маршрутизации</b><small>Редактор JSON/INCY/Happ и активный профиль</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'DNS'}})}><Globe2 size={17}/><span><b>DNS</b><small>Выбор системного DNS-провайдера</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Исключения приложений'}})}><ShieldCheck size={17}/><span><b>Исключения приложений</b><small>Выбор приложений, которые не используют туннель</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Оформление'}})}><Settings2 size={17}/><span><b>Оформление и текст</b><small>Тема, размер шрифта и анимации</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Способ проверки пинга'}})}><Gauge size={17}/><span><b>Метод проверки пинга</b><small>TCP, ICMP или проверка через прокси</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Интервал подписок'}})}><RotateCw size={17}/><span><b>Расписание подписок</b><small>Интервал автообновления от 1 часа до 7 дней</small></span><ChevronRight size={17}/></button></div>}
    {snapshot.platform==='windows'&&<div className="settings-card"><div className="settings-card-title"><Settings2 size={18}/><div><b>Полный функционал Windows</b><span>Расширенные функции старого интерфейса доступны без потери настроек</span></div></div><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Маршрутизация'}})}><Network size={17}/><span><b>Маршрутизация и DNS</b><small>Правила, домены, IP/CIDR, MTU, IPv6 и исключения процессов</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Журнал'}})}><Activity size={17}/><span><b>Журнал и диагностика</b><small>Системный журнал, runtime и диагностика</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Настройки'}})}><Settings2 size={17}/><span><b>Расширенные настройки</b><small>Тема, масштаб текста, автозапуск, обновления и расписание подписок</small></span><ChevronRight size={17}/></button><button className="settings-action" onClick={()=>void onRun({type:'native.openPage',payload:{page:'Импорт'}})}><Plus size={17}/><span><b>Импорт и буфер обмена</b><small>Все штатные варианты импорта профилей и подписок</small></span><ChevronRight size={17}/></button></div>}
    <div className="inline-note"><Smartphone size={17}/><span>Недоступные настройки отключены. После сохранения платформа должна сообщить, применены ли изменения сразу или нужен перезапуск соединения.</span></div>
  </section>;
}
function SettingToggle({title,description,checked,disabled,onChange}:{title:string;description:string;checked:boolean;disabled:boolean;onChange:(value:boolean)=>void}) {return <div className="setting-toggle"><div><b>{title}</b><span>{description}</span></div><label className="switch"><input type="checkbox" checked={checked} disabled={disabled} onChange={e=>onChange(e.target.checked)}/><span/></label></div>;}
