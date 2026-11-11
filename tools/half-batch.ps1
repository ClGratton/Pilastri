param([string]$src,[string]$dst)
Add-Type -AssemblyName System.Drawing
foreach ($f in Get-ChildItem $src -Filter *.png) {
  $i=[System.Drawing.Image]::FromFile($f.FullName); $w=[int]($i.Width/2); $h=[int]($i.Height/2)
  $b=New-Object System.Drawing.Bitmap $w,$h; $g=[System.Drawing.Graphics]::FromImage($b); $g.InterpolationMode='HighQualityBicubic'; $g.DrawImage($i,0,0,$w,$h)
  $b.Save((Join-Path $dst $f.Name),[System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose();$b.Dispose();$i.Dispose()
}
