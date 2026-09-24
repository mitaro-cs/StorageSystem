<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import type { Component } from 'svelte';
	import {
		Archive,
		ChevronLeft,
		ChevronRight,
		Globe,
		GraduationCap,
		History,
		Link2,
		Palette,
		Server,
		ShieldCheck,
		Users
	} from '@lucide/svelte';
	import {
		can,
		canManage,
		currentGroup,
		groups,
		isAdmin,
		isMulti,
		manageMode,
		session,
		setManageMode
	} from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import SectionHead from '$lib/ui/SectionHead.svelte';
	import Invites from '$lib/settings/Invites.svelte';
	import Permissions from '$lib/settings/Permissions.svelte';
	import GroupSettings from '$lib/settings/GroupSettings.svelte';
	import GroupExport from '$lib/settings/GroupExport.svelte';
	import InstancePanel from '$lib/settings/InstancePanel.svelte';
	import Audit from '$lib/settings/Audit.svelte';

	type Tone = 'blue' | 'green' | 'amber' | 'violet' | 'red' | 'teal' | 'gray';
	interface Section {
		value: string;
		label: string;
		desc: string;
		icon: Component<{ size?: number | string }>;
		tone: Tone;
		part: 'group' | 'site';
		show: boolean;
	}

	let picked = $state<number | null>(null);
	const group = $derived(groups().find((g) => g.id === picked) ?? currentGroup() ?? groups()[0]);
	const desktop = $derived(!!session.me?.instance.desktop);

	// Разделы настроек: у каждого своя иконка и одна фраза — что внутри. Группа отдельно от сайта.
	const sections = $derived(
		(
			[
				{
					value: 'accounts',
					label: 'Люди',
					desc: 'Все участники, роли, добавить людей',
					icon: Users,
					tone: 'blue',
					part: 'group',
					show:
						!!group &&
						(can('create_accounts', group.id) ||
							can('create_invites', group.id) ||
							can('block_users', group.id))
				},
				{
					value: 'invites',
					label: 'Приглашения',
					desc: 'Ссылки и QR-коды для вступления',
					icon: Link2,
					tone: 'teal',
					part: 'group',
					show: !!group && can('create_invites', group.id)
				},
				{
					value: 'permissions',
					label: 'Права',
					desc: 'Что могут староста, замы и студенты',
					icon: ShieldCheck,
					tone: 'green',
					part: 'group',
					show: !!group && can('manage_permissions', group.id)
				},
				{
					value: 'group',
					label: 'Группа',
					desc: 'Название, вуз, курс, картинка',
					icon: GraduationCap,
					tone: 'amber',
					part: 'group',
					show: !!group && isAdmin()
				},
				{
					value: 'audit',
					label: 'Журнал',
					desc: 'Кто и что менял',
					icon: History,
					tone: 'gray',
					part: 'group',
					show: !!group && can('view_audit', group.id)
				},
				{
					value: 'export',
					label: 'Архив',
					desc: 'Всё содержимое группы одним ZIP',
					icon: Archive,
					tone: 'gray',
					part: 'group',
					show: !!group && can('export_group', group.id)
				},
				{
					value: 'server',
					label: 'Сервер',
					desc: desktop ? 'Доступ для группы, копии, обновления' : 'Состояние и обновления',
					icon: Server,
					tone: 'teal',
					part: 'site',
					show: isAdmin()
				},
				{
					value: 'instance',
					label: 'Сайт',
					desc: 'Режим, способы входа, администраторы',
					icon: Globe,
					tone: 'violet',
					part: 'site',
					show: isAdmin()
				},
				{
					value: 'appearance',
					label: 'Внешний вид',
					desc: 'Фон страниц входа и регистрации',
					icon: Palette,
					tone: 'red',
					part: 'site',
					show: isAdmin()
				}
			] satisfies Section[]
		).filter((s) => s.show)
	);
	const parts = $derived(
		[
			{ key: 'group', title: isMulti() && group ? `Группа ${group.name}` : 'Группа' },
			{ key: 'site', title: 'Сайт целиком' }
		]
			.map((p) => ({ ...p, items: sections.filter((s) => s.part === p.key) }))
			.filter((p) => p.items.length)
	);

	const asked = $derived(page.url.searchParams.get('tab'));
	// В приложении хоста первым открывается «Сервер»: с него начинается доступ для группы.
	const fallback = $derived(
		desktop && sections.some((s) => s.value === 'server') ? 'server' : sections[0]?.value
	);
	const tab = $derived(sections.some((s) => s.value === asked) ? asked! : fallback);
	const current = $derived(sections.find((s) => s.value === tab));
	/** На телефоне без выбранного раздела показываем меню, а не первый раздел. */
	const menuOnly = $derived(!sections.some((s) => s.value === asked));

	async function enableManage() {
		try {
			await setManageMode(true);
		} catch (e) {
			toastError(e);
		}
	}

	function select(v: string) {
		goto(`/settings?tab=${v}`, { noScroll: true, keepFocus: true });
	}
</script>

<svelte:head><title>{current ? `${current.label} · ` : ''}Настройки · groupbase</title></svelte:head
>

<div class="page-head">
	<h1>Настройки</h1>
	{#if isMulti() && current?.part === 'group'}
		<select
			class="select pick"
			value={group?.id}
			onchange={(e) => (picked = Number(e.currentTarget.value))}
			aria-label="Группа"
		>
			{#each groups() as g (g.id)}<option value={g.id}>{g.name}</option>{/each}
		</select>
	{/if}
</div>

{#if !manageMode() && canManage()}
	<div class="card">
		<Empty
			title="Режим управления выключен"
			text="Кнопки администратора и старосты скрыты — сайт выглядит так же, как у участников."
		>
			<Button variant="primary" onclick={enableManage}>Включить режим управления</Button>
		</Empty>
	</div>
{:else if sections.length === 0}
	<div class="card">
		<Empty
			title="Здесь пока нечего настраивать"
			text="Настройки группы доступны старосте. Свой профиль, тема и пароль — в разделе «Профиль»."
		>
			<a href="/profile">Открыть профиль</a>
		</Empty>
	</div>
{:else}
	<div class="layout" class:menu-only={menuOnly}>
		<nav class="menu" aria-label="Разделы настроек">
			{#each parts as p (p.key)}
				<p class="part">{p.title}</p>
				<div class="items">
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
		</nav>

		{#if current}
			<section class="content" aria-labelledby="settings-title">
				<button class="back" onclick={() => goto('/settings', { noScroll: true })}
					><ChevronLeft size={18} /> Все настройки</button
				>
				<div class="head">
					<SectionHead
						icon={current.icon}
						tone={current.tone}
						title={current.label}
						text={current.desc}
						id="settings-title"
					/>
				</div>
				{#key tab + (group?.id ?? '')}
					<div class="panel">
						{#if group && tab === 'invites'}<Invites groupId={group.id} />
						{:else if tab === 'accounts'}
							{#await import('$lib/content/People.svelte') then m}<m.default />{/await}
						{:else if group && tab === 'permissions'}<Permissions groupId={group.id} />
						{:else if group && tab === 'group'}<GroupSettings {group} />
						{:else if tab === 'instance'}<InstancePanel />
						{:else if tab === 'appearance'}
							{#await import('$lib/settings/AppearancePanel.svelte') then m}<m.default />{/await}
						{:else if tab === 'server'}
							<!-- Вкладка «Сервер» нужна только хосту — её код грузится при открытии. -->
							{#await import('$lib/settings/server/ServerPanel.svelte') then m}<m.default />{/await}
						{:else if group && tab === 'audit'}<Audit groupId={isAdmin() ? null : group.id} />
						{:else if group && tab === 'export'}<GroupExport
								groupId={group.id}
								groupName={group.name}
							/>
						{/if}
					</div>
				{/key}
			</section>
		{/if}
	</div>
{/if}

<style>
	.pick {
		width: auto;
		min-width: 160px;
	}
	.layout {
		display: grid;
		grid-template-columns: 280px minmax(0, 1fr);
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
	.part {
		margin: var(--s2) 6px 0;
		font-size: 12px;
		font-weight: 700;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		color: var(--text-3);
	}
	.items {
		display: flex;
		flex-direction: column;
		padding: 6px;
		border-radius: var(--r-l);
		background: var(--surface);
		box-shadow: var(--shadow-1);
	}
	.item {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px;
		border: 0;
		border-radius: 12px;
		background: transparent;
		color: var(--text);
		font: inherit;
		text-align: left;
		transition: background-color var(--dur) var(--ease);
	}
	.item:hover {
		background: var(--surface-2);
	}
	.item.on {
		background: var(--surface-2);
		box-shadow: inset 3px 0 0 var(--text);
	}
	.ic {
		flex: none;
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border-radius: 11px;
		color: var(--c);
		background: color-mix(in srgb, var(--c) 13%, transparent);
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
		font-size: 15px;
	}
	.txt span {
		color: var(--text-3);
		font-size: 12.5px;
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
	/* Телефон и узкое окно: либо меню разделов, либо открытый раздел с кнопкой «назад». */
	@media (max-width: 900px) {
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
			padding: 12px 10px;
		}
		.item :global(.chev) {
			display: block;
		}
		.item.on {
			box-shadow: none;
		}
		/* В меню на телефоне раздел ещё не выбран — ничего не подсвечиваем. */
		.menu-only .item.on {
			background: transparent;
		}
		.back {
			display: inline-flex;
		}
	}
</style>
