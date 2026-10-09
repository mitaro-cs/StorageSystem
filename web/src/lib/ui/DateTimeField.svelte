<script lang="ts">
	import DateField from './DateField.svelte';
	import TimeField from './TimeField.svelte';

	// Дата и время одним значением 'YYYY-MM-DDTHH:MM' (как у datetime-local): два своих поля рядом.
	let {
		value = $bindable(''),
		id,
		label,
		required = false,
		defaultTime = '23:59',
		onchange
	}: {
		value: string;
		id?: string;
		/** Подпись поля (для времени – «…, время»). */
		label: string;
		required?: boolean;
		/** Время, если человек выбрал только день. */
		defaultTime?: string;
		onchange?: () => void;
	} = $props();

	const day = $derived(value.slice(0, 10));
	const time = $derived(value.slice(11, 16));

	function setDay(d: string) {
		value = d ? `${d}T${time || defaultTime}` : '';
		onchange?.();
	}
	function setTime(t: string) {
		if (!t) return;
		value = `${day || new Date().toISOString().slice(0, 10)}T${t}`;
		onchange?.();
	}
</script>

<div class="dt">
	<DateField {id} value={day} {required} onchange={setDay} />
	<TimeField value={time} label="{label}, время" {required} onchange={setTime} />
</div>

<style>
	.dt {
		display: grid;
		grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
		gap: 8px;
	}
</style>
