<script lang="ts">
	import { toggleDone } from '$lib/content/homework';
	import HomeworkRow from '$lib/content/HomeworkRow.svelte';
	import type { Homework } from '$lib/types';

	// Задания «Сдать в этот день» на «Расписании» — отдельно, чтобы страница укладывалась в бюджет.
	let { items, now }: { items: Homework[]; now: number } = $props();
</script>

<div class="list">
	{#each items as h (h.id)}<HomeworkRow item={h} {now} ontoggle={toggleDone} />{/each}
</div>
