<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { onMount, type Component } from 'svelte';
	import {
		Bell,
		CalendarDays,
		ChevronLeft,
		ChevronRight,
		GraduationCap,
		LogOut,
		Palette,
		ShieldCheck,
		SlidersHorizontal,
		Smartphone,
		UserRound,
		Users,
		WifiOff
	} from '@lucide/svelte';
	import { t } from '$lib/i18n/ru';
	import { currentGroup, groups, hasSettings, session } from '$lib/session.svelte';
	import { sessionNavVisible } from '$lib/content/session';
	import { canModerate, moderation } from '$lib/moderation.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import SectionHead from '$lib/ui/SectionHead.svelte';

	// Свои настройки – у каждого: аккаунт (ФИО, вход, резервные коды, данные) отдельно от настроек
	// приложения (оформление, уведомления, без интернета). Управление группой и сайтом – в /settings.
	type Tone = 'blue' | 'green' | 'amber' | 'violet' | 'red' | 'teal' | 'gray';
	interface Section {
		value: string;
		label: string;
		desc: string;
		icon: Component<{ size?: number | string }>;
		tone: Tone;
		part: 'account' | 'app';
	}

	const SECTIONS: Section[] = [
		{
			value: 'account',
			label: 'Профиль',
			desc: 'Фото, ФИО, логин и роли',
			icon: UserRound,
			tone: 'blue',
			part: 'account'
		},
		{
			value: 'security',
			label: 'Вход и безопасность',
			desc: 'Пароль, 2FA и резервные коды, ключи входа',
			icon: ShieldCheck,
			tone: 'green',
			part: 'account'
		},
		{
			value: 'appearance',
			label: 'Оформление',
			desc: 'Тема, дизайн и цвет',
			icon: Palette,
			tone: 'violet',
			part: 'app'
		},
		{
			value: 'notifications',
			label: 'Уведомления',
			desc: 'Что присылать на телефон',
			icon: Bell,
			tone: 'amber',
			part: 'app'
		},
		{
			value: 'offline',
			label: 'Без интернета',
			desc: 'Что хранится на этом устройстве',
			icon: WifiOff,
			tone: 'teal',
			part: 'app'
		},
		{
			value: 'app',
			label: 'Приложение',
			desc: 'Установка на телефон, тур по сайту, версия',
			icon: Smartphone,
			tone: 'red',
			part: 'app'
		}
	];
	const PARTS = [
		{ key: 'account', title: 'Аккаунт' },
		{ key: 'app', title: 'Приложение' }
	].map((p) => ({ ...p, items: SECTIONS.filter((s) => s.part === p.key) }));

	const me = $derived(session.me!);
	const asked = $derived(page.url.searchParams.get('tab'));
	const tab = $derived(SECTIONS.some((s) => s.value === asked) ? asked! : 'account');
	const current = $derived(SECTIONS.find((s) => s.value === tab)!);
	/** На телефоне без выбранного раздела – профиль и меню, а не первый раздел. */
	const menuOnly = $derived(!SECTIONS.some((s) => s.value === asked));

	// «Сессия» – как в боковой панели компьютера: около сессии или по выбору старосты.
	const showSession = $derived(
		(currentGroup() ? [currentGroup()!] : groups()).some((g) => sessionNavVisible(g, Date.now()))
	);

	// Старые ссылки вида /profile#notifications ведут в нужный раздел.
	onMount(() => {
		const hash = location.hash.slice(1);
		if (SECTIONS.some((s) => s.value === hash)) select(hash, true);
	});

	function select(v: string, replaceState = false) {
		goto(`/profile?tab=${v}`, { noScroll: true, keepFocus: true, replaceState });
	}
</script>

<svelte:head><title>{menuOnly ? '' : `${current.label} · `}Настройки · Campus</title></svelte:head>

<div class="page-head"><h1>{t.nav.mySettings}</h1></div>

<!-- Ширину считает контейнер: под масштабом интерфейса медиазапросы видят окно целиком (0.9.7). -->
<div class="profile">
	<div class="layout" class:menu-only={menuOnly}>
		<nav class="menu" aria-label="Разделы настроек">
			<a class="me card" href="/profile?tab=account" aria-label="Профиль">
				<Avatar id={me.user.id} name={me.user.displayName} avatar={me.user.avatar} size={52} ring />
				<span class="me-txt">
					<strong>{me.user.displayName}</strong>
					<span
						>@{me.user.username}{me.user.instanceRole
							? ` · ${t.roles[me.user.instanceRole]}`
							: ''}</span
					>
				</span>
			</a>

			<!-- На телефоне: разделы, которых нет в нижней панели. -->
			<div class="quick mobile">
				<a href="/schedule"><CalendarDays size={18} /> <span>{t.nav.schedule}</span></a>
				<a href="/members"><Users size={18} /> <span>{t.nav.members}</span></a>
				{#if canModerate()}<a href="/moderation"
						><ShieldCheck size={18} />
						<span>{t.nav.moderation}</span>{#if moderation.reports + moderation.pending}<b
								class="count num">{moderation.reports + moderation.pending}</b
							>{/if}</a
					>{/if}
				{#if showSession}<a href="/session"
						><GraduationCap size={18} /> <span>{t.nav.session}</span></a
					>{/if}
				<a href="/notifications"><Bell size={18} /> <span>{t.nav.notifications}</span></a>
				{#if hasSettings()}<a href="/settings"
						><SlidersHorizontal size={18} /> <span>{t.nav.settings}</span></a
					>{/if}
			</div>

			{#each PARTS as p (p.key)}
				<h2 class="part">{p.title}</h2>
				<div class="items card">
					{#each p.items as s (s.value)}
						<button
							class="item"
							class:on={s.value === tab}
							aria-current={s.value === tab ? 'page' : undefined}
							onclick={() => select(s.value)}
						>
							<span class="ic {s.tone}"><s.icon size={19} /></span>
							<span class="txt"><strong>{s.label}</strong><span>{s.desc}</span></span>
							<ChevronRight size={16} class="chev" />
						</button>
					{/each}
				</div>
			{/each}
			<!-- Выйти – на этом устройстве (код выхода грузится по нажатию). -->
			<button
				class="item card logout"
				onclick={() => import('$lib/profile/logout').then((m) => m.logout())}
			>
				<span class="ic red"><LogOut size={19} /></span>
				<span class="txt"><strong>Выйти</strong><span>На этом устройстве</span></span>
			</button>
		</nav>

		<section class="content" aria-labelledby="profile-section">
			<button class="back" onclick={() => goto('/profile', { noScroll: true })}
				><ChevronLeft size={18} /> Все настройки</button
			>
			<div class="head card">
				<SectionHead
					icon={current.icon}
					tone={current.tone}
					title={current.label}
					text={current.desc}
					id="profile-section"
				/>
			</div>
			{#key tab}
				<div class="panel">
					<!-- Каждый раздел грузит свой код при открытии: страница открывается быстро. -->
					{#if tab === 'account'}
						{#await import('$lib/profile/AccountPanel.svelte') then m}<m.default />{/await}
					{:else if tab === 'security'}
						{#await import('$lib/profile/SecurityPanel.svelte') then m}<m.default />{/await}
					{:else if tab === 'appearance'}
						{#await import('$lib/shell/ThemePicker.svelte') then m}
							<section class="card pane"><m.default /></section>
						{/await}
					{:else if tab === 'notifications'}
						{#await import('$lib/settings/NotificationSettings.svelte') then m}<m.default />{/await}
					{:else if tab === 'offline'}
						{#await import('$lib/settings/OfflineSettings.svelte') then m}<m.default />{/await}
					{:else if tab === 'app'}
						{#await import('$lib/profile/AppPanel.svelte') then m}<m.default />{/await}
					{/if}
				</div>
			{/key}
		</section>
	</div>
</div>

<style>
	.profile {
		container: profile / inline-size;
	}
	.layout {
		display: grid;
		grid-template-columns: 340px minmax(0, 1fr);
		gap: var(--s5);
		align-items: start;
	}
	.menu {
		position: sticky;
		top: var(--s4);
		display: flex;
		flex-direction: column;
		gap: var(--s2);
	}
	.me {
		display: flex;
		align-items: center;
		gap: 14px;
		padding: 14px;
		color: var(--text);
	}
	.me:hover {
		text-decoration: none;
	}
	.me-txt {
		display: flex;
		flex-direction: column;
		min-width: 0;
		line-height: 1.3;
	}
	.me-txt strong {
		font-size: 16px;
		font-weight: 650;
		overflow-wrap: anywhere;
	}
	.me-txt span {
		color: var(--text-2);
		font-size: 13px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.part {
		margin: var(--s3) 6px 0;
		font-size: 12px;
		font-weight: 700;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		color: var(--text-3);
	}
	/* Как «Управление» (0.9.6, по референсу-дашборду): спокойный список, тонкие значки, выбранный –
	   мягкая заливка и тёмный значок. */
	.items {
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding: 8px;
		border-radius: 26px;
		background: var(--surface);
		box-shadow: var(--shadow-1);
	}
	.item {
		position: relative;
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px 12px;
		border: 0;
		border-radius: 16px;
		background: transparent;
		color: var(--text-2);
		font: inherit;
		text-align: left;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.item:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.item.on {
		background: var(--surface-2);
		color: var(--text);
	}
	.item.on .txt strong {
		font-weight: 700;
	}
	.item.on .ic {
		background: var(--text);
		color: var(--bg);
	}
	.ic {
		flex: none;
		display: grid;
		place-items: center;
		width: 38px;
		height: 38px;
		border-radius: 12px;
		color: var(--text-2);
		box-shadow: inset 0 0 0 1px var(--border);
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.ic.red {
		color: var(--c);
	}
	.blue {
		--c: #3a6ff0;
	}
	.green {
		--c: #1f9d57;
	}
	.amber {
		--c: #d98a00;
	}
	.violet {
		--c: #8b5cf6;
	}
	.red {
		--c: #e0483e;
	}
	.teal {
		--c: #0e9fa8;
	}
	.gray {
		--c: var(--text-2);
	}
	.txt {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		line-height: 1.3;
	}
	.txt strong {
		font-weight: 620;
		font-size: 16.5px;
	}
	.txt span {
		color: var(--text-2);
		font-size: 13.5px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.item :global(.chev) {
		flex: none;
		color: var(--text-3);
		display: none;
	}
	.content {
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s4);
	}
	.head {
		padding: var(--s4) var(--s5);
		border-radius: var(--r-l);
		background: var(--surface);
		box-shadow: var(--shadow-1);
	}
	.panel {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		animation: panel-in 200ms var(--ease);
	}
	@keyframes panel-in {
		from {
			opacity: 0;
			transform: translateY(6px);
		}
	}
	.back {
		display: none;
		align-items: center;
		gap: 4px;
		align-self: flex-start;
		padding: 6px 10px 6px 4px;
		border: 0;
		border-radius: 10px;
		background: none;
		color: var(--text-2);
		font: inherit;
		font-weight: 600;
	}
	.quick {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 8px;
		margin: var(--s2) 0;
	}
	.quick a {
		display: flex;
		align-items: center;
		gap: 10px;
		height: 48px;
		padding: 0 14px;
		border-radius: var(--r);
		background: var(--surface);
		box-shadow: var(--shadow-1);
		color: var(--text);
		font-weight: 550;
	}
	.quick a:hover {
		text-decoration: none;
	}
	/* Узкий телефон: значок не сжимается, длинная подпись («Уведомления») – с многоточием. */
	.quick a :global(svg) {
		flex: none;
	}
	.quick span {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.quick .count {
		margin-left: auto;
		min-width: 22px;
		height: 22px;
		padding: 0 6px;
		border-radius: 11px;
		background: var(--amber);
		color: var(--bg);
		font-size: 12px;
		line-height: 22px;
		text-align: center;
	}
	@container profile (min-width: 821px) {
		.mobile {
			display: none;
		}
	}
	/* Телефон и узкое окно: либо профиль с меню разделов, либо открытый раздел с «назад». */
	@container profile (max-width: 820px) {
		.layout {
			grid-template-columns: minmax(0, 1fr);
		}
		.menu {
			position: static;
		}
		.layout:not(.menu-only) .menu {
			display: none;
		}
		.layout.menu-only .content {
			display: none;
		}
		.item {
			padding: 14px 12px;
		}
		.item :global(.chev) {
			display: block;
		}
		.item.on {
			box-shadow: none;
		}
		.menu-only .item.on {
			background: transparent;
		}
		.back {
			display: inline-flex;
		}
	}
</style>
