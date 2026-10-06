<script lang="ts">
	import { onMount } from 'svelte';
	import TermsText from '$lib/terms/TermsText.svelte';
	import { loadTerms, type TermsView } from '$lib/terms/terms';

	// Правила сайта – открываются и без входа (ссылка со страницы входа и из форм регистрации).
	let terms = $state<TermsView | null>(null);
	onMount(() => {
		loadTerms()
			.then((t) => (terms = t))
			.catch(() => {});
	});
</script>

<svelte:head><title>Правила и конфиденциальность · Campus</title></svelte:head>

<h1>Правила и конфиденциальность</h1>
<TermsText extraHtml={terms?.extraHtml ?? ''} />
<p class="back"><a href="/">← На сайт</a></p>

<style>
	h1 {
		margin: 0 0 var(--s4);
		font-size: 26px;
	}
	.back {
		margin-top: var(--s5);
	}
</style>
