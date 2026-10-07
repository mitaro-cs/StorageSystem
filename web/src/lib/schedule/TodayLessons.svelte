<script lang="ts">
	import { startOfDay } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import type { Lesson } from '$lib/types';
	import LessonCard from './LessonCard.svelte';
	import { addDays } from './lessons';

	// «Сегодня»: все пары дня – прошедшие остаются, но приглушены (0.9.6), идущая – с полоской; когда на
	// сегодня всё – пары завтра.
	let { lessons, now }: { lessons: Lesson[]; now: number } = $props();

	const today = $derived(lessons.filter((l) => startOfDay(l.startsAt) === startOfDay(now)));
	const left = $derived(today.filter((l) => l.endsAt > now));
	const tomorrow = $derived(
		lessons.filter((l) => startOfDay(l.startsAt) === addDays(startOfDay(now), 1))
	);
	const shown = $derived(left.length ? today : tomorrow);
</script>

{#if shown.length}
	<section class="lessons" aria-labelledby="today-lessons">
		<div class="section-head">
			<h2 id="today-lessons">{left.length ? 'Пары сегодня' : 'Завтра'}</h2>
			<a class="more-link" href="/schedule">Расписание</a>
		</div>
		<div class="list">
			{#each shown as l, i (l.id)}
				<div in:fly={{ y: 8, delay: stagger(i) }}><LessonCard lesson={l} {now} /></div>
			{/each}
		</div>
		{#if !left.length && today.length}
			<p class="faint small passed">Сегодня пары закончились – вот что завтра.</p>
		{/if}
	</section>
{/if}

<style>
	.lessons {
		margin-bottom: var(--s6);
	}
	.passed {
		margin: var(--s2) 4px 0;
	}
</style>
