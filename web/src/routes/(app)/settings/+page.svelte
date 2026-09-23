<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { can, currentGroup, groups, isAdmin, isMulti } from '$lib/session.svelte';
	import Tabs from '$lib/ui/Tabs.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Invites from '$lib/settings/Invites.svelte';
	import Accounts from '$lib/settings/Accounts.svelte';
	import Permissions from '$lib/settings/Permissions.svelte';
	import GroupSettings from '$lib/settings/GroupSettings.svelte';
	import InstancePanel from '$lib/settings/InstancePanel.svelte';
	import Audit from '$lib/settings/Audit.svelte';

	let picked = $state<number | null>(null);
	const group = $derived(groups().find((g) => g.id === picked) ?? currentGroup() ?? groups()[0]);

	const tabs = $derived(
		[
			{ value: 'invites', label: 'Приглашения', show: !!group && can('create_invites', group.id) },
			{ value: 'accounts', label: 'Аккаунты', show: !!group && can('create_accounts', group.id) },
			{
				value: 'permissions',
				label: 'Права',
				show: !!group && can('manage_permissions', group.id)
			},
			{ value: 'group', label: 'Группа', show: !!group && isAdmin() },
			{ value: 'instance', label: 'Инстанс', show: isAdmin() },
			{ value: 'audit', label: 'Журнал', show: !!group && can('view_audit', group.id) }
		].filter((t) => t.show)
	);
	const tab = $derived(
		tabs.some((t) => t.value === page.url.searchParams.get('tab'))
			? page.url.searchParams.get('tab')!
			: tabs[0]?.value
	);

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

{#if tabs.length === 0}
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
	{:else if group && tab === 'audit'}<Audit groupId={isAdmin() ? null : group.id} />
	{/if}
{/if}

<style>
	.pick {
		width: auto;
		min-width: 160px;
	}
</style>
