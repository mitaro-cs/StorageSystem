<script lang="ts">
	import { untrack } from 'svelte';
	import { patch, post } from '$lib/api';
	import { subjects } from '$lib/data.svelte';
	import { plural } from '$lib/format';
	import { toast } from '$lib/toasts.svelte';
	import type { Lesson, LessonKind } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import { LESSON_KINDS } from './lessons';

	// Пара вручную: предмет, вид, день и время, аудитория, тема; новую можно повторить на несколько
	// недель вперёд. Или правка пары — edit.
	let {
		open = $bindable(),
		groupId,
		edit = null,
		day = Date.now(),
		onsaved
	}: {
		open: boolean;
		groupId: number;
		edit?: Lesson | null;
		/** День новой пары по умолчанию. */
		day?: number;
		onsaved: (l: Lesson) => void;
	} = $props();

	/** Пара в вузе — 1 ч 35 мин (две половины и перерыв). */
	const LENGTH = 95;

	let subjectId = $state<number>(0);
	let title = $state('');
	let kind = $state<LessonKind>('lecture');
	let date = $state('');
	let start = $state('09:30');
	let end = $state('11:05');
	let endTouched = $state(false);
	let place = $state('');
	let teacher = $state('');
	let note = $state('');
	let repeat = $state(0);
	let busy = $state(false);
	let error = $state('');

	const options = $derived(
		subjects.list.filter((s) => !s.archived && s.groups.some((g) => g.id === groupId))
	);
	const p2 = (n: number) => String(n).padStart(2, '0');
	const dateOf = (ms: number) => {
		const d = new Date(ms);
		return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`;
	};
	const timeOf = (ms: number) => {
		const d = new Date(ms);
		return `${p2(d.getHours())}:${p2(d.getMinutes())}`;
	};
	const dirty = $derived(!!(title || place || note) || (edit ? note !== edit.note : false));

	$effect(() => {
		if (!open) return;
		untrack(() => {
			subjectId = edit ? (edit.subject?.id ?? 0) : (options[0]?.id ?? 0);
			title = edit && !edit.subject ? edit.title : '';
			kind = edit?.kind ?? 'lecture';
			date = dateOf(edit?.startsAt ?? day);
			start = edit ? timeOf(edit.startsAt) : '09:30';
			end = edit ? timeOf(edit.endsAt) : '11:05';
			endTouched = !!edit;
			place = edit?.place ?? '';
			teacher = edit?.teacher ?? '';
			note = edit?.note ?? '';
			repeat = 0;
			error = '';
		});
	});

	/** Начало сдвинули — конец едет следом, пока его не трогали. */
	function moveStart(v: string) {
		start = v;
		if (endTouched || !/^\d{2}:\d{2}$/.test(v)) return;
		const [h, m] = v.split(':').map(Number);
		const t = h * 60 + m + LENGTH;
		end = `${p2(Math.floor(t / 60) % 24)}:${p2(t % 60)}`;
	}

	async function save(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		const startsAt = new Date(`${date}T${start}`).getTime();
		const endsAt = new Date(`${date}T${end}`).getTime();
		if (!Number.isFinite(startsAt) || !Number.isFinite(endsAt)) {
			error = 'Укажите день и время';
			return;
		}
		if (endsAt <= startsAt) {
			error = 'Пара заканчивается позже, чем начинается';
			return;
		}
		busy = true;
		try {
			const body = {
				subjectId,
				title: subjectId ? '' : title,
				kind,
				startsAt,
				endsAt,
				place,
				teacher,
				note
			};
			if (edit) {
				const l = await patch<Lesson>(`/api/lessons/${edit.id}`, body);
				toast('Пара изменена', 'ok');
				open = false;
				onsaved(l);
			} else {
				const made = await post<Lesson[]>(`/api/groups/${groupId}/lessons`, {
					...body,
					repeatWeeks: repeat
				});
				toast(made.length > 1 ? `Добавлено пар: ${made.length}` : 'Пара добавлена', 'ok');
				open = false;
				onsaved(made[0]);
			}
		} catch (err) {
			error = err instanceof Error ? err.message : 'Не сохранилось';
		} finally {
			busy = false;
		}
	}
</script>

<Modal bind:open {dirty} title={edit ? 'Изменить пару' : 'Новая пара'} wide>
	<form id="lesson-form" class="stack form" onsubmit={save}>
		<div class="kinds" role="radiogroup" aria-label="Вид занятия">
			{#each LESSON_KINDS as k (k.value)}
				<button
					type="button"
					role="radio"
					aria-checked={kind === k.value}
					class="pill"
					class:ink={kind === k.value}
					onclick={() => (kind = k.value)}>{k.label}</button
				>
			{/each}
		</div>
		<div class="grid">
			<div>
				<label class="label" for="l-subject">Предмет</label>
				<select id="l-subject" class="select" bind:value={subjectId}>
					{#each options as s (s.id)}<option value={s.id}>{s.name}</option>{/each}
					<option value={0}>Без предмета</option>
				</select>
			</div>
			{#if !subjectId}
				<div>
					<label class="label" for="l-title">Название</label>
					<input
						id="l-title"
						class="input"
						bind:value={title}
						maxlength="200"
						required
						placeholder="Кураторский час"
					/>
				</div>
			{/if}
		</div>
		<div class="grid three">
			<div>
				<label class="label" for="l-date">День</label>
				<input id="l-date" class="input num" type="date" bind:value={date} required />
			</div>
			<div>
				<label class="label" for="l-start">Начало</label>
				<input
					id="l-start"
					class="input num"
					type="time"
					value={start}
					oninput={(e) => moveStart(e.currentTarget.value)}
					required
				/>
			</div>
			<div>
				<label class="label" for="l-end">Конец</label>
				<input
					id="l-end"
					class="input num"
					type="time"
					bind:value={end}
					oninput={() => (endTouched = true)}
					required
				/>
			</div>
		</div>
		<div class="grid">
			<div>
				<label class="label" for="l-place"
					>Аудитория <span class="faint">(необязательно)</span></label
				>
				<input
					id="l-place"
					class="input"
					bind:value={place}
					maxlength="80"
					placeholder="ауд. 214"
				/>
			</div>
			<div>
				<label class="label" for="l-teacher"
					>Преподаватель <span class="faint">(необязательно)</span></label
				>
				<input id="l-teacher" class="input" bind:value={teacher} maxlength="120" />
			</div>
		</div>
		<div>
			<label class="label" for="l-note"
				>Тема и заметка <span class="faint">(необязательно)</span></label
			>
			<textarea
				id="l-note"
				class="textarea"
				rows="3"
				bind:value={note}
				maxlength="2000"
				placeholder="Лекция 3. Производные — принести калькулятор"></textarea>
		</div>
		{#if !edit}
			<div>
				<label class="label" for="l-repeat">Повторять</label>
				<select id="l-repeat" class="select" bind:value={repeat}>
					<option value={0}>Только этот день</option>
					{#each [1, 2, 3, 4, 7, 11, 15, 17] as n (n)}
						<option value={n}
							>Каждую неделю: ещё {n} {plural(n, ['неделю', 'недели', 'недель'])}</option
						>
					{/each}
				</select>
			</div>
		{/if}
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="lesson-form" loading={busy}>
			{edit ? 'Сохранить' : 'Добавить'}
		</Button>
	{/snippet}
</Modal>

<style>
	.form {
		gap: var(--s4);
	}
	.kinds {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--s3);
	}
	.grid.three {
		grid-template-columns: 1.4fr 1fr 1fr;
	}
	@media (max-width: 520px) {
		.grid,
		.grid.three {
			grid-template-columns: 1fr;
		}
	}
</style>
