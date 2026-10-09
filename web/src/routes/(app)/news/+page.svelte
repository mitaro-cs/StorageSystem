<script lang="ts">
	import NewsFeed from '$lib/content/NewsFeed.svelte';
	import { tipOpen } from '$lib/onboarding.svelte';
	// Подсказка – вместе со страницей: подгруженная следом, она сдвигала страницу вниз.
	import Tip from '$lib/tour/Tip.svelte';
</script>

<svelte:head><title>Новости · Campus</title></svelte:head>

{#if tipOpen('news')}
	<Tip id="news" text="Важное староста закрепляет вверху. Срочные новости – с жёлтой плашкой." />
{/if}
<NewsFeed />
