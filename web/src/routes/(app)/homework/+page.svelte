<script lang="ts">
	import HomeworkBoard from '$lib/content/HomeworkBoard.svelte';
	import { tipOpen } from '$lib/onboarding.svelte';
	// Подсказка – вместе со страницей: подгруженная следом, она сдвигала страницу вниз.
	import Tip from '$lib/tour/Tip.svelte';
</script>

<svelte:head><title>Домашние задания · Campus</title></svelte:head>

{#if tipOpen('homework')}
	<Tip
		id="homework"
		text="Сделали – отметьте кружком, задание уйдёт вниз. «Календарь» – все сроки на месяц, «Просрочено» – что не сдано."
	/>
{/if}
<HomeworkBoard />
