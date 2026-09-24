<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
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
	import Tabs from '$lib/ui/Tabs.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Invites from '$lib/settings/Invites.svelte';
	import Accounts from '$lib/settings/Accounts.svelte';
	import Permissions from '$lib/settings/Permissions.svelte';
	import GroupSettings from '$lib/settings/GroupSettings.svelte';
	import GroupExport from '$lib/settings/GroupExport.svelte';
	import InstancePanel from '$lib/settings/InstancePanel.svelte';
	import Audit from '$lib/settings/Audit.svelte';

	let picked = $state<number | null>(null);
	const group = $derived(groups().find((g) => g.id === picked) ?? currentGroup() ?? groups()[0]);

	// В приложении хоста «Сервер» — первая вкладка: с неё начинается доступ для группы.
	const desktop = $derived(!!session.me?.instance.desktop);
	const tabs = $derived(
		[
			{ value: 'server', label: 'Сервер', show: isAdmin() && desktop },
			{ value: 'invites', label: 'Приглашения', show: !!group && can('create_invites', group.id) },
			{ value: 'accounts', label: 'Аккаунты', show: !!group && can('create_accounts', group.id) },
			{
				value: 'permissions',
				label: 'Права',
				show: !!group && can('manage_permissions', group.id)
			},
			{ value: 'group', label: 'Группа', show: !!group && isAdmin() },
			{ value: 'instance', label: 'Инстанс', show: isAdmin() },
			{ value: 'server', label: 'Сервер', show: isAdmin() && !desktop },
			{ value: 'audit', label: 'Журнал', show: !!group && can('view_audit', group.id) },
			{ value: 'export', label: 'Архив', show: !!group && can('export_group', group.id) }
		].filter((t) => t.show)
	);
	const tab = $derived(
		tabs.some((t) => t.value === page.url.searchParams.get('tab'))
			? page.url.searchParams.get('tab')!
			: tabs[0]?.value
	);

	async function enableManage() {
		try {
			await setManageMode(true);
		} catch (e) {
			toastError(e);
		}
	}

	function select(v: string) {
		goto(`/settings?tab=${v}`, { replaceState: true, noScroll: true, keepFocus: true });
	}
</script>

<svelte:head><title>Настройки · groupbase</title></svelte:head>

<div class="page-head">
	<h1>Настройки</h1>
	{#if isMulti() && tab !== 'instance'}
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
{:else if tabs.length === 0}
	<div class="card">
		<Empty
			title="Здесь пока нечего настраивать"
			text="Настройки группы доступны старосте. Свой профиль, тема и пароль — в разделе «Профиль»."
		>
			<a href="/profile">Открыть профиль</a>
		</Empty>
	</div>
{:else}
	<Tabs
		tabs={tabs.map((t) => ({ label: t.label, value: t.value }))}
		value={tab}
		onchange={select}
		label="Разделы настроек"
	/>
	{#if group && tab === 'invites'}<Invites groupId={group.id} />
	{:else if group && tab === 'accounts'}<Accounts groupId={group.id} groupName={group.name} />
	{:else if group && tab === 'permissions'}<Permissions groupId={group.id} />
	{:else if group && tab === 'group'}<GroupSettings {group} />
	{:else if tab === 'instance'}<InstancePanel />
	{:else if tab === 'server'}
		<!-- Вкладка «Сервер» нужна только хосту — её код грузится при открытии. -->
		{#await import('$lib/settings/server/ServerPanel.svelte') then m}<m.default />{/await}
	{:else if group && tab === 'audit'}<Audit groupId={isAdmin() ? null : group.id} />
	{:else if group && tab === 'export'}<GroupExport groupId={group.id} groupName={group.name} />
	{/if}
{/if}

<style>
	.pick {
		width: auto;
		min-width: 160px;
	}
</style>
