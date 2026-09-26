<script lang="ts">
	import { get, qs } from '$lib/api';
	import { fmtDate, fmtTime, relativeDay } from '$lib/format';
	import type { AuditEntry } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import { auditLabel, auditLink, auditTarget } from './auditLabels';

	// Журнал действий: кто и что менял — по-русски, с названием записи и ссылкой на неё.
	let { groupId }: { groupId: number | null } = $props();
	let items = $state<AuditEntry[]>([]);
	let done = $state(false);

	async function load(before?: number) {
		const base = groupId === null ? '/api/admin/audit' : `/api/groups/${groupId}/audit`;
		const page = await get<AuditEntry[]>(base + qs({ before }));
		items = before ? [...items, ...page] : page;
		done = page.length < 50;
	}

	$effect(() => {
		void groupId;
		load();
	});

	/** Записи по дням: «Сегодня», «Вчера», «12 сентября». */
	const days = $derived.by(() => {
		const out: { day: string; items: AuditEntry[] }[] = [];
		for (const e of items) {
			const rel = relativeDay(e.at);
			const day = rel === 'сегодня' ? 'Сегодня' : rel === 'вчера' ? 'Вчера' : fmtDate(e.at);
			if (out.at(-1)?.day !== day) out.push({ day, items: [] });
			out.at(-1)!.items.push(e);
		}
		return out;
	});
</script>

{#each days as d (d.day)}
	<p class="day">{d.day}</p>
	<div class="list">
		{#each d.items as e (e.id)}
			{@const target = auditTarget(e)}
			{@const link = auditLink(e)}
			<div class="list-row">
				<span class="time faint num">{fmtTime(e.at)}</span>
				<p class="what">
					<strong>{e.actorName || 'Система'}</strong>
					{auditLabel(e.action)}
					{#if target && link}<a href={link}>«{target}»</a>{:else if target}<span class="target"
							>«{target}»</span
						>{/if}
				</p>
			</div>
		{/each}
	</div>
{:else}
	<p class="faint empty">Записей нет</p>
{/each}
{#if !done && items.length}
	<div class="more">
		<Button variant="ghost" onclick={() => load(items[items.length - 1].id)}>Показать ещё</Button>
	</div>
{/if}

<style>
	.day {
		margin: var(--s3) 6px var(--s2);
		font-size: 12.5px;
		font-weight: 700;
		letter-spacing: 0.05em;
		text-transform: uppercase;
		color: var(--text-3);
	}
	.day:first-child {
		margin-top: 0;
	}
	.list-row {
		align-items: baseline;
		min-height: 52px;
	}
	.time {
		flex: none;
		width: 44px;
		font-size: 13px;
	}
	.what {
		flex: 1;
		min-width: 0;
		margin: 0;
		color: var(--text-2);
		overflow-wrap: anywhere;
	}
	.what strong {
		color: var(--text);
		font-weight: 620;
	}
	.what a,
	.target {
		color: var(--text);
		font-weight: 550;
	}
	.empty {
		padding: var(--s4);
		text-align: center;
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--s3);
	}
</style>
