<script lang="ts">
	import { Archive, Check, ArrowLeft, ArrowRight } from '@lucide/svelte';
	import { post, put } from '$lib/api';
	import { loadSubjects, subjects } from '$lib/data.svelte';
	import { loadMe } from '$lib/session.svelte';
	import { toast } from '$lib/toasts.svelte';
	import { fmtDate, plural } from '$lib/format';
	import type { MeGroup } from '$lib/types';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Switch from '$lib/ui/Switch.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import { parseSubjects, pickColors } from './semester';
	import { dayToMs, phase } from './session';

	// Мастер нового семестра: старые предметы — в архив, новые — списком, даты сессии.
	let { open = $bindable(), group }: { open: boolean; group: MeGroup } = $props();

	const steps = ['Что продолжается', 'Новые предметы', 'Сессия', 'Проверка'];
	let step = $state(0);
	/** id предметов, которые уходят в архив. */
	let archive = $state<number[]>([]);
	let text = $state('');
	let from = $state('');
	let to = $state('');
	let clearOld = $state(false);
	let busy = $state(false);
	let errors = $state<string[]>([]);

	const current = $derived(
		subjects.list.filter((s) => !s.archived && s.groups.some((g) => g.id === group.id))
	);
	const kept = $derived(current.filter((s) => !archive.includes(s.id)));
	const parsed = $derived(
		parseSubjects(
			text,
			kept.map((s) => s.name)
		)
	);
	const fresh = $derived(parsed.filter((s) => !s.duplicate));
	const colors = $derived(
		pickColors(
			fresh.length,
			kept.map((s) => s.color)
		)
	);
	const oldSession = $derived(group.session);
	const oldOver = $derived(phase(oldSession, Date.now()).kind === 'after');

	$effect(() => {
		if (!open) return;
		step = 0;
		archive = [];
		text = '';
		from = '';
		to = '';
		errors = [];
		clearOld = false;
		if (!subjects.loaded) loadSubjects();
	});
	$effect(() => {
		// Прошлая сессия закончилась — по умолчанию её даты убираем.
		if (open) clearOld = oldOver;
	});

	function toggle(id: number, keep: boolean) {
		archive = keep ? archive.filter((x) => x !== id) : [...archive, id];
	}

	const datesValid = $derived((!from && !to) || (!!from && !!to && to >= from));

	async function finish() {
		busy = true;
		errors = [];
		const fail = (what: string, e: unknown) =>
			errors.push(`${what}: ${e instanceof Error ? e.message : 'ошибка'}`);
		for (const id of archive) {
			const s = current.find((x) => x.id === id);
			try {
				await put(`/api/subjects/${id}/archived`, { value: true });
			} catch (e) {
				fail(`В архив «${s?.name}»`, e);
			}
		}
		for (let i = 0; i < fresh.length; i++) {
			const s = fresh[i];
			try {
				await post(`/api/groups/${group.id}/subjects`, {
					name: s.name,
					teacher: s.teacher,
					color: colors[i]
				});
			} catch (e) {
				fail(`Предмет «${s.name}»`, e);
			}
		}
		try {
			if (from && to) {
				await put(`/api/groups/${group.id}/session`, { from: dayToMs(from), to: dayToMs(to) });
			} else if (oldSession && clearOld) {
				await put(`/api/groups/${group.id}/session`, { from: null, to: null });
			}
		} catch (e) {
			fail('Даты сессии', e);
		}
		await Promise.all([loadSubjects(), loadMe()]);
		busy = false;
		if (errors.length === 0) {
			toast('Новый семестр готов', 'ok');
			open = false;
		}
	}
</script>

<Modal bind:open title="Новый семестр" wide>
	<ol class="stepper" aria-label="Шаги">
		{#each steps as s, i (s)}
			<li class:on={i === step} class:passed={i < step}>
				<span class="dot num"
					>{#if i < step}<Check size={13} />{:else}{i + 1}{/if}</span
				>
				<span class="lbl">{s}</span>
			</li>
		{/each}
	</ol>

	{#if step === 0}
		<p class="muted lead">
			Выключите предметы, которые закончились. Они уйдут в архив: задания и файлы сохранятся,
			предмет можно вернуть.
		</p>
		{#if current.length === 0}
			<p class="hint">Предметов пока нет — сразу к новым.</p>
		{:else}
			<div class="list">
				{#each current as s (s.id)}
					{@const keep = !archive.includes(s.id)}
					<div class="subj" class:gone={!keep}>
						<SubjectGlyph id={s.id} name={s.name} color={s.color} size={34} />
						<span class="txt">
							<strong>{s.name}</strong>
							<span class="faint small"
								>{keep ? 'продолжается' : 'в архив'}{s.groups.length > 1
									? ` · общий с ${s.groups
											.filter((g) => g.id !== group.id)
											.map((g) => g.name)
											.join(', ')}`
									: ''}</span
							>
						</span>
						<Switch
							checked={keep}
							label="{s.name} продолжается"
							onchange={(v: boolean) => toggle(s.id, v)}
						/>
					</div>
				{/each}
			</div>
		{/if}
	{:else if step === 1}
		<p class="muted lead">
			По одному на строку. Преподавателя можно через тире: «Физика — Иванов И. И.». Иконка
			подберётся по названию.
		</p>
		<textarea
			class="input area"
			bind:value={text}
			rows="7"
			placeholder="Физика — Иванов И. И.&#10;Сети связи — Петрова А. А.&#10;Английский язык"
			aria-label="Новые предметы"></textarea>
		{#if parsed.length}
			<div class="list preview">
				{#each parsed as s, i (s.name)}
					<div class="subj" class:gone={s.duplicate}>
						<SubjectGlyph
							name={s.name}
							color={s.duplicate ? '#9ca3af' : colors[fresh.indexOf(s)]}
							icon={null}
							size={30}
						/>
						<span class="txt">
							<strong>{s.name}</strong>
							<span class="faint small"
								>{s.duplicate ? 'уже есть — пропустим' : s.teacher || 'без преподавателя'}</span
							>
						</span>
						<span class="faint small num">{i + 1}</span>
					</div>
				{/each}
			</div>
		{/if}
	{:else if step === 2}
		<p class="muted lead">
			Когда сессия? За три недели до начала у всех на главной появится обратный отсчёт. Можно
			пропустить и задать позже в «Настройки → Семестр».
		</p>
		<div class="grid">
			<div>
				<label class="label" for="ns-from">Первый день</label>
				<input id="ns-from" class="input num" type="date" bind:value={from} />
			</div>
			<div>
				<label class="label" for="ns-to">Последний день</label>
				<input id="ns-to" class="input num" type="date" bind:value={to} min={from} />
			</div>
		</div>
		{#if !datesValid}<p class="error-text">Укажите оба дня; последний — не раньше первого.</p>{/if}
		{#if oldSession && !(from && to)}
			<label class="check">
				<input type="checkbox" bind:checked={clearOld} />
				Убрать прошлые даты ({fmtDate(oldSession.from)} — {fmtDate(oldSession.to)})
			</label>
		{/if}
	{:else}
		<ul class="summary">
			<li>
				<Archive size={17} />
				<span>
					{#if archive.length}
						В архив: <strong>{archive.length}</strong>
						{plural(archive.length, ['предмет', 'предмета', 'предметов'])}
					{:else}
						Все предметы продолжаются
					{/if}</span
				>
			</li>
			<li>
				<Check size={17} />
				<span>
					{#if fresh.length}
						Новых: <strong>{fresh.length}</strong>
						{plural(fresh.length, ['предмет', 'предмета', 'предметов'])} — {fresh
							.map((s) => s.name)
							.join(', ')}
					{:else}
						Новых предметов нет
					{/if}</span
				>
			</li>
			<li>
				<Check size={17} />
				<span>
					{#if from && to}
						Сессия: <strong class="num">{fmtDate(dayToMs(from))} — {fmtDate(dayToMs(to))}</strong>
					{:else if oldSession && clearOld}
						Прошлые даты сессии уберём
					{:else}
						Даты сессии не меняются
					{/if}</span
				>
			</li>
		</ul>
		{#if errors.length}
			<div class="error-text" role="alert">
				<p>Не всё получилось:</p>
				<ul>
					{#each errors as e (e)}<li>{e}</li>{/each}
				</ul>
			</div>
		{/if}
	{/if}

	{#snippet footer()}
		{#if step > 0}
			<Button variant="ghost" onclick={() => (step -= 1)} disabled={busy}
				><ArrowLeft size={16} /> Назад</Button
			>
		{/if}
		<span class="spacer"></span>
		{#if step < steps.length - 1}
			<Button variant="primary" onclick={() => (step += 1)} disabled={step === 2 && !datesValid}
				>Дальше <ArrowRight size={16} /></Button
			>
		{:else}
			<Button variant="primary" onclick={finish} loading={busy}>
				{errors.length ? 'Повторить' : 'Готово'}
			</Button>
		{/if}
	{/snippet}
</Modal>

<style>
	.stepper {
		display: flex;
		gap: 6px;
		list-style: none;
		margin: 0 0 var(--s4);
		padding: 0;
	}
	.stepper li {
		flex: 1;
		min-width: 0;
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 10px;
		border-radius: 12px;
		background: var(--surface-2);
		color: var(--text-3);
		font-size: 13px;
		font-weight: 550;
	}
	.stepper li.on {
		background: var(--inverse);
		color: var(--inverse-text);
	}
	.stepper li.passed {
		color: var(--text-2);
	}
	.dot {
		flex: none;
		display: grid;
		place-items: center;
		width: 22px;
		height: 22px;
		border-radius: 50%;
		background: var(--surface);
		color: var(--text-2);
		font-size: 12px;
	}
	.on .dot {
		background: var(--inverse-2);
		color: var(--inverse-text);
	}
	.lbl {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	@media (max-width: 560px) {
		.stepper li:not(.on) .lbl {
			display: none;
		}
		.stepper li:not(.on) {
			flex: none;
		}
	}
	.lead {
		margin-bottom: var(--s4);
	}
	.list {
		display: flex;
		flex-direction: column;
		gap: 2px;
		border-radius: var(--r);
		overflow: hidden;
	}
	.preview {
		margin-top: var(--s3);
	}
	.subj {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px 12px;
		background: var(--surface-2);
		transition: opacity var(--dur) var(--ease);
	}
	.subj.gone {
		opacity: 0.55;
	}
	.txt {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.txt strong {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.area {
		height: auto;
		min-height: 150px;
		padding: 12px 14px;
		resize: vertical;
		line-height: 1.5;
	}
	.grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--s3);
	}
	@media (max-width: 420px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
	.check {
		display: flex;
		align-items: center;
		gap: 10px;
		margin-top: var(--s4);
		color: var(--text-2);
	}
	.summary {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.summary li {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		line-height: 1.45;
	}
	.summary :global(svg) {
		flex: none;
		margin-top: 2px;
		color: var(--text-2);
	}
</style>
