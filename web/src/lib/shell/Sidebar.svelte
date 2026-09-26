<script lang="ts">
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { PanelLeftClose, PanelLeftOpen, Search } from '@lucide/svelte';
	import { openPalette } from './palette.svelte';
	import { t } from '$lib/i18n/ru';
	import {
		canManage,
		currentGroup,
		isMulti,
		session,
		setManageMode,
		visibleNav
	} from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import Switch from '$lib/ui/Switch.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import GroupSwitcher from './GroupSwitcher.svelte';
	import SubjectList from './SubjectList.svelte';
	import ThemeToggle from './ThemeToggle.svelte';
	import { isActive, mainNav } from './nav';
	import { canModerate, moderation, refreshModeration } from '$lib/moderation.svelte';
	import { appIcon, iconSrc } from '$lib/appIcon.svelte';
	import { sessionNavVisible } from '$lib/content/session';

	let { collapsed = $bindable(false) }: { collapsed?: boolean } = $props();

	onMount(() => {
		try {
			collapsed = localStorage.getItem('gb-sidebar') === 'collapsed';
		} catch {
			/* по умолчанию развёрнута */
		}
	});

	function toggle() {
		collapsed = !collapsed;
		try {
			localStorage.setItem('gb-sidebar', collapsed ? 'collapsed' : 'open');
		} catch {
			/* не запоминаем */
		}
	}

	const group = $derived(currentGroup());
	// Жалобы и материалы на проверке — число у «Модерации»; обновляется и живыми обновлениями.
	const waiting = $derived(canModerate() ? moderation.reports + moderation.pending : 0);
	onMount(() => {
		refreshModeration();
		const id = setInterval(refreshModeration, 5 * 60_000);
		return () => clearInterval(id);
	});
	// «Сессия» — только когда её показывает староста (или около сессии); в режиме «все группы» —
	// если она нужна хоть одной группе.
	const showSession = $derived(
		(group ? [group] : (session.me?.groups ?? [])).some((g) => sessionNavVisible(g, Date.now()))
	);
	// Файлы — во вкладке «Материалы» каждого предмета, уведомления — колокольчиком на «Сегодня».
	// В панели их нет, но командная палитра (⌘K) и горячие клавиши их находят.
	const items = $derived(
		visibleNav(mainNav).filter(
			(i) =>
				i.href !== '/materials' &&
				i.href !== '/notifications' &&
				(i.href !== '/session' || showSession)
		)
	);
	const title = $derived(
		group?.name ??
			(isMulti()
				? session.me?.instance.name || t.nav.allGroups
				: (session.me?.groups[0]?.name ?? ''))
	);
</script>

<aside class="sidebar" class:collapsed aria-label="Навигация">
	<div class="top">
		{#if !collapsed}<GroupSwitcher />{/if}
		<!-- Иконка группы (её картинка или буквы на цвете), у «Всех групп» — логотип сайта. -->
		<div class="title" class:only-mark={collapsed}>
			<span class="mark" title={collapsed ? title : undefined}>
				{#if group}
					<Avatar
						id={group.id}
						name={group.name}
						avatar={group.avatar}
						size={40}
						kind="group"
						square
					/>
				{:else}
					<img src={iconSrc(appIcon.id)} alt="" width="40" height="40" />
				{/if}
			</span>
			{#if !collapsed}
				<span class="names">
					<strong>{title}</strong>
					{#if group?.university}<span class="faint small"
							>{group.university}{group.course ? `, ${group.course} курс` : ''}</span
						>{/if}
				</span>
			{/if}
		</div>
	</div>

	<button class="finder" onclick={() => openPalette()} title="Командная палитра (Ctrl/⌘+K)">
		<Search size={17} />
		{#if !collapsed}<span>Найти или перейти</span><kbd>⌘K</kbd>{/if}
	</button>

	<nav class="main">
		{#each items as item (item.href)}
			{@const Icon = item.icon}
			<a
				href={item.href}
				class:active={isActive(page.url.pathname, item.href)}
				aria-current={isActive(page.url.pathname, item.href) ? 'page' : undefined}
				title={collapsed ? item.label : undefined}
			>
				<Icon size={19} strokeWidth={1.8} />
				{#if !collapsed}<span class="label-text">{item.label}</span>{/if}
				{#if item.href === '/moderation' && waiting}<span
						class="badge num"
						title="Ждёт модератора: {waiting}">{waiting}</span
					>{/if}
			</a>
		{/each}
	</nav>

	{#if !collapsed}
		<div class="section">
			<p class="eyebrow">{t.nav.subjects}</p>
			<SubjectList />
		</div>
	{/if}

	{#if !collapsed && session.me && canManage()}
		<div class="manage">
			<span>Режим управления</span>
			<Switch
				checked={session.me.user.manageMode}
				label="Режим управления"
				onchange={(on) => setManageMode(on).catch(toastError)}
			/>
		</div>
	{/if}

	<div class="bottom">
		{#if session.me}
			<a class="me" href="/profile" title={session.me.user.displayName}>
				<Avatar
					id={session.me.user.id}
					name={session.me.user.displayName}
					avatar={session.me.user.avatar}
					size={36}
				/>
				{#if !collapsed}<span class="name">{session.me.user.displayName}</span>{/if}
			</a>
		{/if}
		{#if !collapsed}<ThemeToggle />{/if}
		<button
			class="collapse"
			onclick={toggle}
			aria-label={collapsed ? t.nav.expand : t.nav.collapse}
			title={collapsed ? t.nav.expand : t.nav.collapse}
		>
			{#if collapsed}<PanelLeftOpen size={18} />{:else}<PanelLeftClose size={18} />{/if}
		</button>
	</div>
</aside>

<style>
	.manage {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		margin-top: auto;
		padding: 0 4px;
		color: var(--text-2);
		font-size: 14px;
	}
	.manage + .bottom {
		margin-top: 0;
	}
	.sidebar {
		position: sticky;
		top: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		width: var(--sidebar);
		height: 100dvh;
		padding: var(--s5) var(--s4);
		border-right: 1px solid var(--border);
		background: var(--bg);
		transition: width 220ms var(--ease);
		overflow: hidden;
	}
	.collapsed {
		width: var(--sidebar-collapsed);
		align-items: center;
	}
	.top {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: 0 6px;
	}
	.title {
		display: flex;
		align-items: center;
		gap: 12px;
		min-width: 0;
	}
	.names {
		display: flex;
		flex-direction: column;
		min-width: 0;
		line-height: 1.3;
	}
	.names strong {
		font-size: 20px;
		letter-spacing: -0.025em;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.names .small {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.mark {
		flex: none;
		display: grid;
		width: 40px;
		height: 40px;
		border-radius: 12px;
		overflow: hidden;
		box-shadow:
			0 0 0 1px var(--border),
			0 4px 12px -6px rgb(0 0 0 / 0.35);
		transition: transform 220ms var(--ease);
	}
	.mark img {
		width: 100%;
		height: 100%;
	}
	.title:hover .mark {
		transform: rotate(-4deg) scale(1.04);
	}
	.only-mark {
		justify-content: center;
	}
	.badge {
		min-width: 22px;
		height: 22px;
		padding: 0 6px;
		border-radius: 11px;
		background: var(--amber);
		color: var(--bg);
		font-size: 12px;
		font-weight: 700;
		line-height: 22px;
		text-align: center;
		animation: badge-pop 320ms cubic-bezier(0.3, 1.6, 0.5, 1);
	}
	.collapsed .badge {
		position: absolute;
		top: 2px;
		right: 2px;
		min-width: 18px;
		height: 18px;
		padding: 0 4px;
		font-size: 10.5px;
		line-height: 18px;
	}
	@keyframes badge-pop {
		from {
			transform: scale(0.3);
		}
	}
	/* Поиск-пилюля, как «Search» на макете */
	.finder {
		display: flex;
		align-items: center;
		gap: 10px;
		height: 44px;
		padding: 0 8px 0 16px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-3);
		font-size: 14.5px;
		white-space: nowrap;
	}
	.finder:hover {
		border-color: var(--border-strong);
		color: var(--text-2);
	}
	.finder span {
		flex: 1;
		text-align: left;
	}
	.finder kbd {
		font: 600 11px var(--font);
		padding: 4px 8px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-2);
	}
	.collapsed .finder {
		width: 44px;
		justify-content: center;
		padding: 0;
	}
	.main {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.main a {
		display: flex;
		align-items: center;
		gap: 12px;
		height: 44px;
		padding: 0 16px;
		border-radius: var(--r-full);
		color: var(--text-2);
		font-weight: 540;
		white-space: nowrap;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.main a:hover {
		background: var(--surface);
		color: var(--text);
		text-decoration: none;
	}
	.main a.active {
		background: var(--accent);
		color: var(--accent-text);
	}
	.main a {
		position: relative;
	}
	.label-text {
		flex: 1;
	}
	.collapsed .main a {
		width: 48px;
		height: 48px;
		justify-content: center;
		padding: 0;
	}
	.section {
		flex: 1;
		min-height: 0;
		overflow-y: auto;
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding-top: var(--s2);
		border-top: 1px solid var(--border);
	}
	.section .eyebrow {
		padding: 6px 10px 2px;
	}
	.collapsed .main {
		flex: 1;
	}
	.bottom {
		display: flex;
		align-items: center;
		gap: 4px;
		padding-top: var(--s2);
		border-top: 1px solid var(--border);
	}
	.collapsed .bottom {
		flex-direction: column;
		width: 100%;
	}
	.me {
		display: flex;
		align-items: center;
		gap: 10px;
		flex: 1;
		min-width: 0;
		padding: 4px 10px 4px 4px;
		border-radius: var(--r-full);
		color: var(--text);
		font-weight: 550;
		font-size: 14px;
	}
	.me:hover {
		background: var(--surface);
		text-decoration: none;
	}
	.name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.collapse {
		display: grid;
		place-items: center;
		flex: none;
		width: 40px;
		height: 40px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--text-3);
	}
	.collapse:hover {
		background: var(--surface);
		color: var(--text);
	}
</style>
