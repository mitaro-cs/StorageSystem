<script lang="ts">
	import { ChevronRight, Upload } from '@lucide/svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	// Пустая неделя на «Расписании»: нет пар, ничего не нашлось или расписание ещё не загружено.
	let {
		filtered,
		thisWeek,
		manage,
		onimport,
		onnext
	}: {
		filtered: boolean;
		thisWeek: boolean;
		manage: boolean;
		onimport: () => void;
		onnext: () => void;
	} = $props();
</script>

<div class="card">
	{#if filtered}
		<Empty
			title="Ничего не нашлось"
			text="Попробуйте другой запрос или «Все занятия» — или посмотрите другую неделю."
		>
			<Button onclick={onnext}>Следующая неделя <ChevronRight size={16} /></Button>
		</Empty>
	{:else}
		<Empty
			title={thisWeek ? 'На этой неделе пар нет' : 'В эти дни пар нет'}
			text={manage
				? 'Загрузите расписание из файла календаря (.ics) — например, выгрузку с сайта вуза или из Google Календаря. Пары можно добавить и вручную.'
				: 'Когда староста загрузит расписание, пары появятся здесь — и на «Сегодня».'}
		>
			{#if manage}
				<Button variant="primary" onclick={onimport}
					><Upload size={16} /> Загрузить файл календаря</Button
				>
			{/if}
			<Button onclick={onnext}>Следующая неделя <ChevronRight size={16} /></Button>
		</Empty>
	{/if}
</div>
