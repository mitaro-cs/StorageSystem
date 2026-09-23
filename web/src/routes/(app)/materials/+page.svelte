<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { get, qs } from '$lib/api';
	import { session } from '$lib/session.svelte';
	import { sortedSubjects } from '$lib/data.svelte';
	import { fly, stagger } from '$lib/motion';
	import type { Material } from '$lib/types';
	import { materialActions } from '$lib/content/materialActions';
	import MaterialRow from '$lib/content/MaterialRow.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let recent = $state<Material[] | null>(null);
	let pending = $state<Material[]>([]);

	async function load() {
		const [r, p] = await Promise.all([
			get<Material[]>(`/api/materials/recent${qs({ group: session.groupId, limit: 15 })}`),
			get<Material[]>('/api/materials/pending')
		]);
		recent = r;
		pending = p;
	}

	$effect(() => {
		void session.groupId;
		void offline.version;
		load();
	});

	const subjectList = $derived(sortedSubjects(session.groupId));
</script>

<svelte:head><title>Материалы · groupbase</title></svelte:head>

<div class="page-head"><h1>Материалы</h1></div>

{#if pending.length}
	<section class="block">
		<h2 class="h amber">На проверке · {pending.length}</h2>
		<div class="list">
			{#each pending as m (m.id)}<MaterialRow
					{m}
					showSubject
					actions={materialActions(m, load)}
				/>{/each}
		</div>
	</section>
{/if}

<section class="block">
	<h2 class="h">По предметам</h2>
	{#if subjectList.length === 0}
		<div class="card"><Empty title="Предметов пока нет" /></div>
	{:else}
		<div class="subjects">
			{#each subjectList as s, i (s.id)}
				<a
					class="subject"
					href="/subjects/{s.id}?tab=materials"
					style:--c={s.color}
					in:fly={{ y: 8, delay: stagger(i) }}
				>
					<span class="tab"></span>
					<span class="name">{s.name}</span>
				</a>
			{/each}
		</div>
	{/if}
</section>

<section class="block">
	<h2 class="h">Недавно добавленные</h2>
	{#if recent === null}
		<Skeleton lines={4} />
	{:else if recent.length === 0}
		<div class="card">
			<Empty
				title="Материалов пока нет"
				text="Конспекты, методички и ссылки появятся здесь, когда их добавят в предметы."
			/>
		</div>
	{:else}
		<div class="list">
			{#each recent as m, i (m.id)}
				<div in:fly={{ y: 8, delay: stagger(i) }}>
					<MaterialRow {m} showSubject actions={materialActions(m, load)} />
				</div>
			{/each}
		</div>
	{/if}
</section>

<style>
	.block {
		margin-bottom: var(--s6);
	}
	.h {
		font-size: 18px;
		margin-bottom: var(--s3);
	}
	.h.amber {
		color: var(--amber);
	}
	.subjects {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
		gap: var(--s2);
	}
	.subject {
		position: relative;
		display: flex;
		align-items: flex-end;
		height: 72px;
		padding: 12px 14px;
		border-radius: var(--r);
		background: var(--surface);
		box-shadow: var(--shadow-1);
		color: var(--text);
		font-weight: 560;
		overflow: hidden;
		transition:
			transform var(--dur) var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.subject:hover {
		text-decoration: none;
		transform: translateY(-2px);
		box-shadow: var(--shadow-2);
	}
	.tab {
		position: absolute;
		top: 12px;
		left: 14px;
		width: 22px;
		height: 6px;
		border-radius: 3px;
		background: var(--c);
	}
	.name {
		line-height: 1.25;
		overflow: hidden;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
	}
</style>
