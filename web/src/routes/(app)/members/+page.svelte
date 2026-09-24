<script lang="ts">
	import { t } from '$lib/i18n/ru';
	import People from '$lib/content/People.svelte';
</script>

<svelte:head><title>{t.nav.members} · groupbase</title></svelte:head>

<div class="page-head">
	<h1>{t.nav.members}</h1>
</div>

<People />
