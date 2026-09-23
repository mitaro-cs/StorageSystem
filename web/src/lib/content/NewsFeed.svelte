<script lang="ts">
	import { Plus } from '@lucide/svelte';
	import { get, qs } from '$lib/api';
	import { can, session } from '$lib/session.svelte';
	import { fly, stagger } from '$lib/motion';
	import type { NewsItem, NewsPage } from '$lib/types';
	import { newsActions } from '$lib/content/newsActions';
	import NewsCard from '$lib/content/NewsCard.svelte';
	import NewsComposer from '$lib/content/NewsComposer.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let { subjectId = null }: { subjectId?: number | null } = $props();

	let pinned = $state<NewsItem[]>([]);
	let items = $state<NewsItem[]>([]);
	let next = $state<number | null>(null);
	let loading = $state(true);
	let loadingMore = $state(false);
	let composer = $state(false);
	let editing = $state<NewsItem | null>(null);
	let sentinel: HTMLElement | undefined = $state();

	async function load() {
		loading = true;
		const p = await get<NewsPage>(`/api/news${qs({ group: session.groupId, subject: subjectId })}`);
		pinned = p.pinned;
		items = p.items;
		next = p.next;
		loading = false;
	}

	async function more() {
		if (next === null || loadingMore) return;
		loadingMore = true;
		try {
			const p = await get<NewsPage>(
				`/api/news${qs({ group: session.groupId, subject: subjectId, before: next })}`
			);
			items.push(...p.items);
			next = p.next;
		} finally {
			loadingMore = false;
		}
	}

	$effect(() => {
		void session.groupId;
		load();
	});

	$effect(() => {
		if (!sentinel) return;
		const io = new IntersectionObserver((e) => e[0].isIntersecting && more(), {
			rootMargin: '400px'
		});
		io.observe(sentinel);
		return () => io.disconnect();
	});

	function actions(n: NewsItem) {
		return newsActions(n, {
			edit: () => {
				editing = n;
				composer = true;
			},
			removed: () => {
				items = items.filter((x) => x.id !== n.id);
				pinned = pinned.filter((x) => x.id !== n.id);
			},
			changed: load
		});
	}
</script>

{#if subjectId === null}
	<div class="page-head">
		<h1>Новости</h1>
		{#if can('publish_news')}
			<Button variant="primary" onclick={() => ((editing = null), (composer = true))}
				><Plus size={17} /> Новость</Button
			>
		{/if}
	</div>
{:else if can('publish_news')}
	<div class="row sub-actions">
		<span class="spacer"></span>
		<Button size="s" onclick={() => ((editing = null), (composer = true))}
			><Plus size={16} /> Новость</Button
		>
	</div>
{/if}

{#if loading}
	<div class="stack"><Skeleton /><Skeleton /><Skeleton /></div>
{:else if pinned.length === 0 && items.length === 0}
	<div class="card">
		<Empty title="Новостей пока нет" text="Здесь появятся объявления старосты и преподавателей." />
	</div>
{:else}
	<div class="stack feed">
		{#each [...pinned, ...items] as n, i (n.id)}
			<div in:fly={{ y: 10, delay: stagger(i % 20, 35) }}>
				<NewsCard item={n} actions={actions(n)} />
			</div>
		{/each}
	</div>
	<div bind:this={sentinel} class="sentinel" aria-hidden="true">
		{#if loadingMore}<Skeleton />{/if}
	</div>
{/if}

<NewsComposer bind:open={composer} edit={editing} {subjectId} onsaved={load} />

<style>
	.feed {
		gap: var(--s3);
	}
	.sentinel {
		min-height: 1px;
		margin-top: var(--s3);
	}
	.sub-actions {
		margin-bottom: var(--s3);
	}
</style>
