<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import BackBar from '$lib/ui/BackBar.svelte';
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
		void offline.version;
		load();
	});
</script>

<svelte:head><title>{item?.title ?? 'Новость'} · groupbase</title></svelte:head>

<BackBar href="/news" label="Новости" />

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
