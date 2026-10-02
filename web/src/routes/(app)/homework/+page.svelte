<script lang="ts">
	import HomeworkBoard from '$lib/content/HomeworkBoard.svelte';
	import { tipOpen } from '$lib/onboarding.svelte';
</script>

<svelte:head><title>Домашние задания · groupbase</title></svelte:head>

{#if tipOpen('homework')}
	{#await import('$lib/tour/Tip.svelte') then m}<m.default
			id="homework"
			text="Сделали — отметьте кружком, задание уйдёт вниз. «Календарь» — все сроки на месяц, «Просрочено» — что не сдано."
		/>{/await}
{/if}
<HomeworkBoard />
