<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { ArrowLeft } from '@lucide/svelte';
	import { get } from '$lib/api';
	import type { NewsItem } from '$lib/types';
	import { newsActions } from '$lib/content/newsActions';
	import { can } from '$lib/session.svelte';
	import NewsCard from '$lib/content/NewsCard.svelte';
	import NewsComposer from '$lib/content/NewsComposer.svelte';
	import Comments from '$lib/content/Comments.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let item = $state<NewsItem | null>(null);
	let missing = $state(false);
	let composer = $state(false);

	async function load() {
		try {
			item = await get<NewsItem>(`/api/news/${page.params.id}`);
		} catch {
			missing = true;
		}
	}

	$effect(() => {
		void page.params.id;
		load();
	});
</script>

<svelte:head><title>{item?.title ?? 'Новость'} · groupbase</title></svelte:head>

<a
	class="back"
	href="/news"
	onclick={(e) => {
		if (history.length > 1) {
			e.preventDefault();
			history.back();
		}
	}}><ArrowLeft size={16} /> Новости</a
>

{#if missing}
	<div class="card">
		<Empty title="Новость не найдена" text="Её удалили или она адресована другой группе." />
	</div>
{:else if !item}
	<Skeleton lines={6} />
{:else}
	<NewsCard
		{item}
		full
		actions={newsActions(item, {
			edit: () => (composer = true),
			removed: () => goto('/news', { replaceState: true }),
			changed: load
		})}
	/>
	<Comments base="/api/news/{item.id}" canComment={item.groups.some((g) => can('comment', g.id))} />
	<NewsComposer bind:open={composer} edit={item} onsaved={(n) => (item = n)} />
{/if}

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin-bottom: var(--s4);
		color: var(--text-2);
		font-size: 14px;
		font-weight: 550;
	}
</style>
