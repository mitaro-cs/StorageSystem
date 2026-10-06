<script lang="ts">
	import NewsFeed from '$lib/content/NewsFeed.svelte';
	import { tipOpen } from '$lib/onboarding.svelte';
</script>

<svelte:head><title>Новости · Campus</title></svelte:head>

{#if tipOpen('news')}
	{#await import('$lib/tour/Tip.svelte') then m}<m.default
			id="news"
			text="Важное староста закрепляет вверху. Срочные новости – с жёлтой плашкой."
		/>{/await}
{/if}
<NewsFeed />
