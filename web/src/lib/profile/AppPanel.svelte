<script lang="ts">
	import { BookOpenText, Info, SlidersHorizontal, Smartphone, ZoomIn } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { canToggleManage, isAdmin, session, setManageMode } from '$lib/session.svelte';
	import { welcome } from '$lib/onboarding.svelte';
	import { install, installed, pwa } from '$lib/pwa.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Switch from '$lib/ui/Switch.svelte';

	// Приложение: установка на телефон, знакомство с сайтом, версия; хосту — режим управления.
	const me = $derived(session.me!);

	// Масштаб окна приложения хоста (0.8.1): «Авто» — под монитор, или свой. Выбор помнит оболочка,
	// здесь — только чтобы показать его.
	const ZOOMS = [0, 80, 90, 100, 110, 125, 150];
	let zoom = $state(readZoom());
	function readZoom(): number {
		try {
			return Number(localStorage.getItem('gb-host-zoom') ?? 0) || 0;
		} catch {
			return 0;
		}
	}
	async function setZoom(value: number) {
		try {
			await post('/api/desktop/zoom', { value });
			zoom = value;
			try {
				localStorage.setItem('gb-host-zoom', String(value));
			} catch {
				/* приватный режим — просто не запомним, что показать */
			}
		} catch (e) {
			toastError(e);
		}
	}

	async function setManage(on: boolean) {
		try {
			await setManageMode(on);
			toast(on ? 'Кнопки управления снова видны' : 'Кнопки управления скрыты', 'ok');
		} catch (e) {
			toastError(e);
		}
	}
</script>

{#if canToggleManage()}
	<section class="card pane">
		<div class="pane-title">
			<SlidersHorizontal size={18} />
			<h3>Режим управления</h3>
			<span class="switch"
				><Switch checked={me.user.manageMode} label="Режим управления" onchange={setManage} /></span
			>
		</div>
		<p class="muted small">
			Кнопки администратора: приглашения, права, сервер, модерация. Выключите — и сайт выглядит так
			же, как у участников.
		</p>
	</section>
{/if}

{#if me.hostWindow}
	<section class="card pane">
		<div class="pane-title">
			<ZoomIn size={18} />
			<h3>Масштаб окна</h3>
		</div>
		<p class="muted small">
			«Авто» подбирает размер под монитор. Мелко или крупно — выберите свой; запомнится на этом
			компьютере.
		</p>
		<div class="zooms" role="radiogroup" aria-label="Масштаб окна">
			{#each ZOOMS as z (z)}
				<button
					type="button"
					role="radio"
					aria-checked={zoom === z}
					class:on={zoom === z}
					onclick={() => setZoom(z)}>{z ? `${z}%` : 'Авто'}</button
				>
			{/each}
		</div>
	</section>
{/if}

<section class="card pane">
	<div class="pane-title">
		<Smartphone size={18} />
		<h3>Приложение на телефон</h3>
	</div>
	<p class="muted small">
		{installed()
			? 'Уже установлено: campus открывается с экрана «Домой», без адресной строки.'
			: 'Установите сайт как приложение: откроется без адресной строки, со своим значком, а задания и новости будут под рукой без сети.'}
	</p>
	<div class="row wrap">
		{#if pwa.canInstall}<Button variant="primary" onclick={install}>Установить</Button>{/if}
		<Button href="/install">Как установить — по шагам</Button>
	</div>
</section>

<section class="card pane">
	<div class="pane-title">
		<BookOpenText size={18} />
		<h3>Как пользоваться</h3>
	</div>
	<p class="muted small">
		Тур по сайту — то же, что при первом входе: полминуты, по главным кнопкам.
	</p>
	<div><Button onclick={() => (welcome.open = true)}>Пройти тур</Button></div>
</section>

<section class="card pane">
	<div class="pane-title">
		<Info size={18} />
		<h3>О программе</h3>
	</div>
	<dl class="kv">
		<div>
			<dt>Версия</dt>
			<dd class="num">campus {me.instance.version}</dd>
		</div>
	</dl>
	<div class="row wrap">
		{#if isAdmin()}<Button href="/settings?tab=updates">Проверить обновления</Button>{/if}
		<Button variant="ghost" href="/terms">Правила и конфиденциальность</Button>
	</div>
</section>

<style>
	.switch {
		margin-left: auto;
	}
	.zooms {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.zooms button {
		min-width: 64px;
		padding: 8px 12px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 600;
		cursor: pointer;
	}
	.zooms button.on {
		border-color: transparent;
		background: var(--accent);
		color: var(--accent-text);
	}
</style>
