<script lang="ts">
	import { get } from '$lib/api';
	import { t } from '$lib/i18n/ru';
	import { fly, stagger } from '$lib/motion';
	import type { Member } from '$lib/types';
	import Avatar from '$lib/ui/Avatar.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import { session } from '$lib/session.svelte';

	interface Props {
		groupIds: number[];
		actions?: (m: Member, groupId: number) => MenuItem[];
		reload?: number;
		/** Поиск снаружи (раздел «Люди» в кабинете). */
		query?: string;
		/** Сообщить, кого загрузили — для счётчиков. */
		onload?: (members: Member[]) => void;
	}

	let { groupIds, actions, reload = 0, query: outer = undefined, onload }: Props = $props();
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
				onload?.(rows.map((r) => r.member));
			})
			.catch(() => (rows = []));
	});

	const q = $derived((outer ?? query).trim().toLowerCase());
	const filtered = $derived(
		(rows ?? []).filter(
			(r) =>
				!q || r.member.displayName.toLowerCase().includes(q) || !!r.member.username?.includes(q)
		)
	);
</script>

{#if rows === null}
	<Skeleton lines={5} />
{:else}
	{#if rows.length > 8 && outer === undefined}
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
					{#if m.username}<span class="faint small">@{m.username}</span>{/if}
					{#if m.userId === session.me?.user.id || m.instanceRole || m.role !== 'student' || m.status !== 'active'}
						<span class="tags">
							{#if m.userId === session.me?.user.id}<span class="chip">это вы</span>{/if}
							{#if m.instanceRole}<span class="chip ink">{t.roles[m.instanceRole]}</span>{/if}
							{#if m.role !== 'student'}<span class="chip accent">{t.roles[m.role]}</span>{/if}
							{#if m.status === 'pending'}<span class="chip amber">ещё не вошёл</span>{/if}
							{#if m.status === 'blocked'}<span class="chip danger">заблокирован</span>{/if}
						</span>
					{/if}
				</div>
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
	.tags {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
		margin-top: 4px;
	}
	.tags .chip {
		height: 22px;
		padding: 0 8px;
		font-size: 12px;
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
