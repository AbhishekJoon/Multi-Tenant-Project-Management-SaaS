import { Link } from "react-router-dom";
import {
  Layers3,
  ArrowRight,
  ShieldCheck,
  FolderKanban,
  Users,
} from "lucide-react";
export default function Landing() {
  return (
    <main className="landing">
      <nav>
        <Link className="brand" to="/">
          <Layers3 />
          teamflow<span className="brand-dot">.</span>
        </Link>
        <Link className="button quiet" to="/login">
          Sign in
        </Link>
      </nav>
      <section className="landing-hero">
        <span className="eyebrow">BRING A LITTLE CLARITY TO YOUR DAY</span>
        <h1>
          Great work starts
          <br />
          with <em>good flow.</em>
        </h1>
        <p>
          A shared home for your people, projects, and next big ideas.
          <br />
          Less scattered work. More meaningful progress.
        </p>
        <Link className="button primary" to="/register">
          Create your workspace <ArrowRight size={17} />
        </Link>
      </section>
      <section className="landing-features">
        <article>
          <FolderKanban size={24} />
          <h2>A clear next step</h2>
          <p>
            Plan projects, assign tasks, and move work forward on a board that
            stays in sync.
          </p>
        </article>
        <article>
          <Users size={24} />
          <h2>Better, together</h2>
          <p>
            Invite your team, share context in comments, and stay close to the
            work that matters.
          </p>
        </article>
        <article>
          <ShieldCheck size={24} />
          <h2>A space of your own</h2>
          <p>
            Separate organizations, private projects, and thoughtful permissions
            keep access clear.
          </p>
        </article>
      </section>
      <footer>TeamFlow · A little clarity, every day.</footer>
    </main>
  );
}
