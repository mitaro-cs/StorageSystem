<script lang="ts">
	import { get } from '$lib/api';
	import { t } from '$lib/i18n/ru';
	import { fly, stagger } from '$lib/motion';
	import type { Member } from '$lib/types';
	import Avatar from '$lib/ui/Avatar.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';

	interface Props {
		groupIds: number[];
		actions?: (m: Member, groupId: number) => MenuItem[];
		reload?: number;
	}

	let { groupIds, actions, reload = 0 }: Props = $props();
	let rows = $state<{ member: Member; groupId: number }[] | null>(null);
	let query = $state('');

	$effect(() => {
		void reload;
		const ids = groupIds;
		Promise.all(
			ids.map((g) =>
				get<Member[]>(`/api/groups/${g}/members`).then((ms) =>
					ms.map((m) => ({ member: m, groupId: g }))
				)
			)
		)
			.then((all) => {
				const flat = all.flat();
				rows = flat.filter(
					(r, i) => flat.findIndex((x) => x.member.userId === r.member.userId) === i
				);
			})
			.catch(() => (rows = []));
	});

	const filtered = $derived(
		(rows ?? []).filter(
			(r) =>
				!query ||
				r.member.displayName.toLowerCase().includes(query.toLowerCase()) ||
				r.member.username.includes(query.toLowerCase())
		)
	);
</script>

{#if rows === null}
	<Skeleton lines={5} />
{:else}
	{#if rows.length > 8}
		<input
			class="input filter"
			type="search"
			placeholder="Найти по имени"
			bind:value={query}
			aria-label="Фильтр участников"
		/>
	{/if}
	<div class="list">
		{#each filtered as r, i (r.member.userId)}
			{@const m = r.member}
			<div class="list-row" in:fly={{ y: 6, delay: stagger(i, 20) }}>
				<Avatar id={m.userId} name={m.displayName} avatar={m.avatar} size={36} />
				<div class="who">
					<strong>{m.displayName}</strong>
					<span class="faint small">@{m.username}</span>
				</div>
				{#if m.role !== 'student'}<span class="chip accent">{t.roles[m.role]}</span>{/if}
				{#if m.status === 'pending'}<span class="chip amber">не активирован</span>{/if}
				{#if m.status === 'blocked'}<span class="chip danger">заблокирован</span>{/if}
				{#if actions}<Menu items={actions(m, r.groupId)} />{/if}
			</div>
		{:else}
			<p class="faint empty">Никого не нашли</p>
		{/each}
	</div>
{/if}

<style>
	.filter {
		margin-bottom: var(--s3);
	}
	.who {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		line-height: 1.3;
	}
	.who strong {
		font-weight: 560;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.empty {
		padding: var(--s4);
		text-align: center;
	}
</style>
